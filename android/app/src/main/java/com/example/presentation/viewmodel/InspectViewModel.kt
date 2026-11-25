package com.example.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.InspectionTarget
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.PcbImageSource
import com.example.domain.model.SamplePcbPreset
import com.example.domain.repository.ImageStorageManager
import com.example.domain.repository.ModelManager
import com.example.domain.repository.SettingsRepository
import com.example.domain.usecase.RunInspectionUseCase
import com.example.inference.demo.SamplePcbCatalog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class InspectUiState(
    val selectedTarget: InspectionTarget = InspectionTarget.PCB_BOARD,
    val selectedImageUri: String? = null,
    val selectedSourceType: PcbImageSource? = null,
    val boardName: String = "",
    val sampleScenarioHint: String? = null,
    val validation: ImageValidationResult? = null,
    val isCameraLiveActive: Boolean = false,
    val isValidating: Boolean = false,
    val isRunningInspection: Boolean = false,
    val progressMessage: String? = null,
    val errorMessage: String? = null,
    val detectionModelInfo: ModelInfo,
    val advisoryModelInfo: ModelInfo,
    val transistorModelInfo: ModelInfo,
    val samplePresets: List<SamplePcbPreset> = SamplePcbCatalog.presets
) {
    val canStartInspection: Boolean
        get() {
            if (selectedImageUri == null ||
                validation?.isValid != true ||
                !validation.isProcessable ||
                isValidating ||
                isRunningInspection ||
                isCameraLiveActive
            ) {
                return false
            }
            return when (selectedTarget) {
                InspectionTarget.PCB_BOARD -> true
                InspectionTarget.TRANSISTOR -> {
                    selectedSourceType != PcbImageSource.SAMPLE &&
                        transistorModelInfo.state == ModelRuntimeState.READY
                }
            }
        }
}

class InspectViewModel(
    private val imageStorageManager: ImageStorageManager,
    private val settingsRepository: SettingsRepository,
    private val runInspectionUseCase: RunInspectionUseCase,
    private val modelManager: ModelManager
) : ViewModel() {

    private val inspectionMutex = Mutex()
    private val validationRequestId = AtomicLong(0)

    private val _localState = MutableStateFlow(
        InspectUiState(
            detectionModelInfo = modelManager.detectionModelInfo.value,
            advisoryModelInfo = modelManager.advisoryModelInfo.value,
            transistorModelInfo = modelManager.transistorModelInfo.value
        )
    )

    val uiState: StateFlow<InspectUiState> = combine(
        _localState,
        modelManager.detectionModelInfo,
        modelManager.advisoryModelInfo,
        modelManager.transistorModelInfo
    ) { state, detInfo, advInfo, transInfo ->
        state.copy(
            detectionModelInfo = detInfo,
            advisoryModelInfo = advInfo,
            transistorModelInfo = transInfo
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = _localState.value
    )

    fun onTargetSelected(target: InspectionTarget) {
        _localState.update { current ->
            if (current.selectedTarget == target) return@update current
            val clearSample = target == InspectionTarget.TRANSISTOR && current.selectedSourceType == PcbImageSource.SAMPLE
            val updatedBoardName = when {
                clearSample -> ""
                target == InspectionTarget.TRANSISTOR && (current.boardName.isBlank() || current.boardName == "Uploaded PCB Board" || current.boardName == "Captured PCB Board") -> {
                    if (current.selectedSourceType == PcbImageSource.CAMERA) "Captured Transistor" else if (current.selectedSourceType == PcbImageSource.GALLERY) "Uploaded Transistor" else ""
                }
                target == InspectionTarget.PCB_BOARD && (current.boardName.isBlank() || current.boardName == "Uploaded Transistor" || current.boardName == "Captured Transistor") -> {
                    if (current.selectedSourceType == PcbImageSource.CAMERA) "Captured PCB Board" else if (current.selectedSourceType == PcbImageSource.GALLERY) "Uploaded PCB Board" else ""
                }
                else -> current.boardName
            }
            current.copy(
                selectedTarget = target,
                selectedImageUri = if (clearSample) null else current.selectedImageUri,
                selectedSourceType = if (clearSample) null else current.selectedSourceType,
                validation = if (clearSample) null else current.validation,
                sampleScenarioHint = if (clearSample) null else current.sampleScenarioHint,
                boardName = updatedBoardName,
                errorMessage = null
            )
        }
    }

    fun setLiveCameraActive(active: Boolean) {
        _localState.update {
            it.copy(
                isCameraLiveActive = active,
                errorMessage = null
            )
        }
    }

    suspend fun allocateCameraCaptureDestination(): Pair<String, Uri> =
        imageStorageManager.createCameraCaptureDestination()

    fun onCameraImageCaptured(fileUriString: String, success: Boolean) {
        val reqId = validationRequestId.incrementAndGet()
        viewModelScope.launch {
            if (!success) {
                imageStorageManager.deleteUnreferencedCaptureIfEmpty(fileUriString)
                if (reqId == validationRequestId.get()) {
                    _localState.update {
                        it.copy(
                            isCameraLiveActive = false,
                            errorMessage = "Camera capture cancelled or failed."
                        )
                    }
                }
                return@launch
            }
            val target = _localState.value.selectedTarget
            val suggested = if (target == InspectionTarget.TRANSISTOR) "Captured Transistor" else "Captured PCB Board"
            validateAndSelectImage(
                imageUriString = fileUriString,
                sourceType = PcbImageSource.CAMERA,
                suggestedBoardName = suggested,
                scenarioHint = null,
                expectedRequestId = reqId
            )
        }
    }

    fun onCameraCaptureError(message: String) {
        _localState.update {
            it.copy(
                isCameraLiveActive = false,
                errorMessage = message
            )
        }
    }

    fun onGalleryImagePicked(uri: Uri?) {
        if (uri == null) return
        val reqId = validationRequestId.incrementAndGet()
        viewModelScope.launch {
            _localState.update { it.copy(isValidating = true, errorMessage = null, isCameraLiveActive = false) }
            val importResult = imageStorageManager.importPickedImageToAppStorage(uri)
            if (reqId != validationRequestId.get()) return@launch
            importResult.onSuccess { storedUriString ->
                val target = _localState.value.selectedTarget
                val suggested = if (target == InspectionTarget.TRANSISTOR) "Uploaded Transistor" else "Uploaded PCB Board"
                validateAndSelectImage(
                    imageUriString = storedUriString,
                    sourceType = PcbImageSource.GALLERY,
                    suggestedBoardName = suggested,
                    scenarioHint = null,
                    expectedRequestId = reqId
                )
            }.onFailure { err ->
                if (reqId == validationRequestId.get()) {
                    _localState.update {
                        it.copy(
                            isValidating = false,
                            errorMessage = "Could not import selected photo: ${err.message ?: "unknown error"}"
                        )
                    }
                }
            }
        }
    }

    fun onSamplePresetSelected(preset: SamplePcbPreset) {
        val reqId = validationRequestId.incrementAndGet()
        viewModelScope.launch {
            _localState.update { it.copy(isValidating = true, errorMessage = null, isCameraLiveActive = false) }
            val res = imageStorageManager.materializeSamplePreset(preset)
            if (reqId != validationRequestId.get()) return@launch
            res.onSuccess { uriString ->
                validateAndSelectImage(
                    imageUriString = uriString,
                    sourceType = PcbImageSource.SAMPLE,
                    suggestedBoardName = preset.boardName,
                    scenarioHint = preset.scenarioHint,
                    forceBoardName = true,
                    expectedRequestId = reqId
                )
            }.onFailure { err ->
                if (reqId == validationRequestId.get()) {
                    _localState.update {
                        it.copy(
                            isValidating = false,
                            errorMessage = "Failed to load sample PCB: ${err.message ?: "unknown error"}"
                        )
                    }
                }
            }
        }
    }

    fun onBoardNameChanged(name: String) {
        _localState.update { it.copy(boardName = name) }
    }

    fun clearSelectedImage() {
        validationRequestId.incrementAndGet()
        _localState.update {
            it.copy(
                selectedImageUri = null,
                selectedSourceType = null,
                validation = null,
                sampleScenarioHint = null,
                errorMessage = null,
                isValidating = false
            )
        }
    }

    fun dismissError() {
        _localState.update { it.copy(errorMessage = null) }
    }

    fun startInspection(onCompleted: (String) -> Unit) {
        val ownerToken = Any()
        if (!inspectionMutex.tryLock(ownerToken)) {
            return
        }

        val released = AtomicBoolean(false)
        fun releaseLock() {
            if (released.compareAndSet(false, true)) {
                if (inspectionMutex.holdsLock(ownerToken)) {
                    inspectionMutex.unlock(ownerToken)
                }
            }
        }

        var launched = false
        try {
            val current = _localState.value.copy(
                detectionModelInfo = modelManager.detectionModelInfo.value,
                advisoryModelInfo = modelManager.advisoryModelInfo.value,
                transistorModelInfo = modelManager.transistorModelInfo.value
            )

            if (current.isRunningInspection || current.isValidating) {
                return
            }

            if (current.selectedImageUri == null) {
                _localState.update { it.copy(errorMessage = "Please select or capture an image before starting inspection.") }
                return
            }

            if (current.selectedSourceType == null) {
                _localState.update { it.copy(errorMessage = "Image source type is missing. Please re-select the image.") }
                return
            }

            if (current.isCameraLiveActive) {
                _localState.update { it.copy(errorMessage = "Please capture or dismiss the live camera before starting inspection.") }
                return
            }

            if (current.validation == null || !current.validation.isValid || !current.validation.isProcessable) {
                val validationMsg = current.validation?.errorMessage ?: "Selected image is not processable or below minimum dimensions."
                _localState.update { it.copy(errorMessage = validationMsg) }
                return
            }

            if (current.selectedTarget == InspectionTarget.TRANSISTOR) {
                if (current.selectedSourceType == PcbImageSource.SAMPLE) {
                    _localState.update { it.copy(errorMessage = "Sample presets are not supported for Visual Anomaly inspection.") }
                    return
                }
                if (current.transistorModelInfo.state != ModelRuntimeState.READY) {
                    _localState.update {
                        it.copy(
                            errorMessage = "PatchCore model is ${current.transistorModelInfo.state.name}. Load the PatchCore model in Settings before inspecting for anomalies."
                        )
                    }
                    return
                }
            }

            val uri = current.selectedImageUri
            val source = current.selectedSourceType

            _localState.update {
                it.copy(
                    isRunningInspection = true,
                    progressMessage = "Preparing inspection pipeline",
                    errorMessage = null
                )
            }

            launched = true

            val job = viewModelScope.launch {
                try {
                    val defaultName = if (current.selectedTarget == InspectionTarget.TRANSISTOR) {
                        "Transistor Component"
                    } else if (source == PcbImageSource.SAMPLE) {
                        "Sample PCB"
                    } else {
                        "PCB Assembly"
                    }
                    val record = runInspectionUseCase.execute(
                        imageUriString = uri,
                        sourceType = source,
                        boardName = current.boardName.ifBlank { defaultName },
                        sampleScenarioHint = current.sampleScenarioHint,
                        target = current.selectedTarget,
                        onStageProgress = { stage ->
                            _localState.update { it.copy(progressMessage = stage) }
                        }
                    )
                    _localState.update {
                        it.copy(
                            isRunningInspection = false,
                            progressMessage = null
                        )
                    }
                    onCompleted(record.inspectionId)
                } catch (e: CancellationException) {
                    _localState.update {
                        if (it.isRunningInspection) {
                            it.copy(isRunningInspection = false, progressMessage = null)
                        } else it
                    }
                    throw e
                } catch (e: Throwable) {
                    _localState.update {
                        it.copy(
                            isRunningInspection = false,
                            progressMessage = null,
                            errorMessage = "Inspection failed: ${e.message ?: "unexpected error"}"
                        )
                    }
                } finally {
                    releaseLock()
                }
            }

            if (job.isCancelled) {
                _localState.update {
                    if (it.isRunningInspection) {
                        it.copy(isRunningInspection = false, progressMessage = null)
                    } else it
                }
                releaseLock()
            } else {
                job.invokeOnCompletion { cause ->
                    if (cause != null) {
                        _localState.update {
                            if (it.isRunningInspection) {
                                it.copy(isRunningInspection = false, progressMessage = null)
                            } else it
                        }
                        releaseLock()
                    }
                }
            }
        } finally {
            if (!launched) {
                releaseLock()
            }
        }
    }

    private suspend fun validateAndSelectImage(
        imageUriString: String,
        sourceType: PcbImageSource,
        suggestedBoardName: String,
        scenarioHint: String?,
        forceBoardName: Boolean = false,
        expectedRequestId: Long? = null
    ) {
        _localState.update { it.copy(isValidating = true, isCameraLiveActive = false, errorMessage = null) }
        val policy = settingsRepository.policyConfig.first()
        val validation = imageStorageManager.validateAndAssessClarity(
            imageUriString = imageUriString,
            minWidth = policy.minImageWidth,
            minHeight = policy.minImageHeight
        )
        if (expectedRequestId != null && expectedRequestId != validationRequestId.get()) {
            return
        }
        _localState.update { prev ->
            val isDefaultName = prev.boardName.isBlank() ||
                prev.boardName == "Uploaded PCB Board" ||
                prev.boardName == "Captured PCB Board" ||
                prev.boardName == "Uploaded Transistor" ||
                prev.boardName == "Captured Transistor"
            val resolvedName = when {
                forceBoardName || isDefaultName -> suggestedBoardName
                else -> prev.boardName
            }
            prev.copy(
                selectedImageUri = imageUriString,
                selectedSourceType = sourceType,
                boardName = resolvedName,
                sampleScenarioHint = scenarioHint,
                validation = validation,
                isValidating = false,
                errorMessage = if (!validation.isProcessable || !validation.isValid) {
                    validation.errorMessage ?: "Selected image failed validation."
                } else {
                    null
                }
            )
        }
    }

    class Factory(
        private val imageStorageManager: ImageStorageManager,
        private val settingsRepository: SettingsRepository,
        private val runInspectionUseCase: RunInspectionUseCase,
        private val modelManager: ModelManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return InspectViewModel(
                imageStorageManager = imageStorageManager,
                settingsRepository = settingsRepository,
                runInspectionUseCase = runInspectionUseCase,
                modelManager = modelManager
            ) as T
        }
    }
}

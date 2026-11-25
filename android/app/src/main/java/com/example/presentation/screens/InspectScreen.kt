package com.example.presentation.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.domain.model.InspectionTarget
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.PcbImageSource
import com.example.domain.model.SamplePcbPreset
import com.example.inference.demo.SamplePcbCatalog
import com.example.presentation.components.BrandHeader
import com.example.presentation.components.ClarityStatusBadge
import com.example.presentation.components.DemonstrationBadge
import com.example.presentation.components.HairlineDivider
import com.example.presentation.components.RuntimeStatusDialog
import com.example.presentation.components.TonalNoticeBanner
import com.example.presentation.components.VerticalHairline
import com.example.presentation.viewmodel.InspectUiState
import com.example.ui.theme.BharatSpacing
import com.example.ui.theme.LocalPcbColors
import com.example.ui.theme.MonoTextStyles
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectScreen(
    uiState: InspectUiState,
    allocateCameraCaptureDestination: suspend () -> Pair<String, Uri>,
    onSetLiveCameraActive: (Boolean) -> Unit,
    onCameraImageCaptured: (String, Boolean) -> Unit,
    onCameraCaptureError: (String) -> Unit,
    onGalleryImagePicked: (Uri?) -> Unit,
    onSamplePresetSelected: (SamplePcbPreset) -> Unit,
    onBoardNameChanged: (String) -> Unit,
    onClearSelectedImage: () -> Unit,
    onDismissError: () -> Unit,
    onStartInspectionClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onTargetSelected: (InspectionTarget) -> Unit = {}
) {
    val context = LocalContext.current
    val colors = LocalPcbColors.current
    val coroutineScope = rememberCoroutineScope()
    var showRuntimeDialog by remember { mutableStateOf(false) }
    var permissionDeniedBanner by remember { mutableStateOf(false) }
    var pendingSystemCameraFileUri by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = uiState.isCameraLiveActive) {
        onSetLiveCameraActive(false)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        onGalleryImagePicked(uri)
    }

    val systemCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val fileUri = pendingSystemCameraFileUri
        pendingSystemCameraFileUri = null
        if (fileUri != null) {
            onCameraImageCaptured(fileUri, success)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedBanner = false
            onSetLiveCameraActive(true)
        } else {
            permissionDeniedBanner = true
        }
    }

    fun handleCapturePcbClick() {
        val hasCameraPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPerm) {
            permissionDeniedBanner = false
            onSetLiveCameraActive(true)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun launchImagePicker() {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    if (showRuntimeDialog) {
        RuntimeStatusDialog(
            detectionModelInfo = uiState.detectionModelInfo,
            advisoryModelInfo = uiState.advisoryModelInfo,
            transistorModelInfo = uiState.transistorModelInfo,
            onOpenSettings = onOpenSettingsClick,
            onDismiss = { showRuntimeDialog = false }
        )
    }

    val activeModelReady = if (uiState.selectedTarget == InspectionTarget.TRANSISTOR) {
        uiState.transistorModelInfo.state == ModelRuntimeState.READY
    } else {
        uiState.detectionModelInfo.state == ModelRuntimeState.READY
    }
    val isTransistor = uiState.selectedTarget == InspectionTarget.TRANSISTOR

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("inspect_screen"),
        contentPadding = PaddingValues(horizontal = BharatSpacing.content, vertical = BharatSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.lg)
    ) {
        item {
            BrandHeader(
                onRuntimeStatusClick = { showRuntimeDialog = true },
                hasRuntimeAlert = !activeModelReady
            )
            Spacer(modifier = Modifier.height(BharatSpacing.xs))
            Text(
                text = "New Inspection",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Acquire & validate ${if (isTransistor) "component" else "PCB"} optical frame",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Single moderately rounded Material 3 segmented mode selector
        item {
            val targets = listOf(
                InspectionTarget.PCB_BOARD to "PCB Board (YOLO)",
                InspectionTarget.TRANSISTOR to "Visual Anomaly (PatchCore)"
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inspect_target_selector")
            ) {
                targets.forEachIndexed { index, (target, label) ->
                    val selected = uiState.selectedTarget == target
                    SegmentedButton(
                        selected = selected,
                        onClick = { onTargetSelected(target) },
                        enabled = !uiState.isRunningInspection,
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = targets.size,
                            baseShape = MaterialTheme.shapes.medium
                        ),
                        icon = {},
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.testTag(
                            if (target == InspectionTarget.PCB_BOARD) "inspect_target_pcb"
                            else "inspect_target_transistor"
                        )
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (permissionDeniedBanner) {
            item {
                TonalNoticeBanner(
                    title = "Camera permission denied",
                    message = if (isTransistor) {
                        "You can still upload an inspection image from Photo Picker or retry camera permission."
                    } else {
                        "You can still upload a PCB image from Photo Picker, load a sample board below, or retry camera permission."
                    },
                    icon = Icons.Outlined.WarningAmber,
                    containerColor = colors.reviewBg,
                    contentColor = colors.reviewFg,
                    iconTint = colors.reviewFg
                )
            }
        }

        if (uiState.errorMessage != null) {
            item {
                TonalNoticeBanner(
                    message = uiState.errorMessage,
                    icon = Icons.Outlined.WarningAmber,
                    containerColor = colors.rejectBg,
                    contentColor = colors.rejectFg,
                    iconTint = colors.rejectFg,
                    onDismiss = onDismissError,
                    dismissContentDescription = "Dismiss error",
                    modifier = Modifier.testTag("inspect_error_banner")
                )
            }
        }

        // Dominant PCB Preview (no outer card frame around selected image)
        item {
            PcbPreviewViewport(
                uiState = uiState,
                allocateCameraCaptureDestination = allocateCameraCaptureDestination,
                onCameraImageCaptured = onCameraImageCaptured,
                onCameraCaptureError = onCameraCaptureError,
                onCancelLiveCamera = { onSetLiveCameraActive(false) },
                onUseSystemCameraFallback = {
                    coroutineScope.launch {
                        val (fileUri, contentUri) = allocateCameraCaptureDestination()
                        pendingSystemCameraFileUri = fileUri
                        systemCameraLauncher.launch(contentUri)
                    }
                },
                onClearSelectedImage = onClearSelectedImage
            )
        }

        // Clean underline Material 3 TextField for Board Identifier / Name
        if (uiState.selectedImageUri != null && !uiState.isCameraLiveActive) {
            item {
                TextField(
                    value = uiState.boardName,
                    onValueChange = onBoardNameChanged,
                    label = {
                        Text(
                            text = if (isTransistor) "Component Identifier / Name" else "Board Identifier / Name",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inspect_board_name_input")
                )
            }
        }

        if (uiState.selectedSourceType != null &&
            uiState.selectedSourceType != PcbImageSource.SAMPLE
        ) {
            if (!isTransistor && uiState.detectionModelInfo.state != ModelRuntimeState.READY) {
                item {
                    TonalNoticeBanner(
                        title = "YOLOv8n ONNX detector is ${uiState.detectionModelInfo.state.name}",
                        message = "Real camera/gallery images never fall back to demo inference. Running now will record INCOMPLETE until you import a .onnx model in Settings.",
                        icon = Icons.Outlined.Info,
                        containerColor = colors.incompleteBg,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        iconTint = colors.incompleteFg,
                        actionLabel = "Settings",
                        onActionClick = onOpenSettingsClick,
                        modifier = Modifier.testTag("inspect_production_model_warning")
                    )
                }
            } else if (isTransistor && uiState.transistorModelInfo.state != ModelRuntimeState.READY) {
                item {
                    TonalNoticeBanner(
                        title = "PatchCore Anomaly Detector is ${uiState.transistorModelInfo.state.name}",
                        message = "Visual anomaly inspection requires an imported and loaded PatchCore model. You must load the model in Settings before starting inspection.",
                        icon = Icons.Outlined.Info,
                        containerColor = colors.incompleteBg,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        iconTint = colors.incompleteFg,
                        actionLabel = "Settings",
                        onActionClick = onOpenSettingsClick,
                        modifier = Modifier.testTag("inspect_transistor_model_warning")
                    )
                }
            }
        }

        // Open Inspection Inputs strip (Capture | Upload Image | Upload Schematic)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.md)) {
                Text(
                    text = "Inspection Inputs",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InspectionInputAction(
                        icon = Icons.Outlined.CameraAlt,
                        label = if (isTransistor) "Capture\nComponent" else "Capture PCB",
                        contentDescription = "Capture Optical Frame",
                        enabled = !uiState.isRunningInspection,
                        onClick = { handleCapturePcbClick() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inspect_capture_pcb_button")
                    )

                    VerticalHairline(height = 36.dp)

                    InspectionInputAction(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = if (isTransistor) "Upload\nComponent" else "Upload\nPCB Image",
                        contentDescription = "Upload Optical Frame",
                        enabled = !uiState.isRunningInspection,
                        onClick = { launchImagePicker() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inspect_upload_pcb_button")
                    )

                    VerticalHairline(height = 36.dp)

                    InspectionInputAction(
                        icon = Icons.Outlined.Description,
                        label = if (isTransistor) "Upload\nSchematic" else "Upload\nPCB Schematic",
                        contentDescription = "Upload PCB Schematic",
                        enabled = !uiState.isRunningInspection,
                        onClick = { launchImagePicker() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inspect_upload_schematic_button")
                    )
                }
            }
        }

        // Primary Start Inspection CTA (strictly gated)
        item {
            Button(
                onClick = onStartInspectionClick,
                enabled = uiState.canStartInspection,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("inspect_start_button"),
                shape = MaterialTheme.shapes.medium,
                elevation = null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                if (uiState.isRunningInspection || uiState.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = uiState.progressMessage ?: "Validating image...",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.CenterFocusWeak,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(BharatSpacing.sm))
                    Text(
                        text = "Start Inspection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (!isTransistor) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(BharatSpacing.xs)) {
                    HairlineDivider()
                    Spacer(modifier = Modifier.height(BharatSpacing.sm))
                    Text(
                        text = "Prototype Sample Boards (Quick Load)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        uiState.samplePresets.forEachIndexed { index, preset ->
                            SamplePresetRow(
                                preset = preset,
                                enabled = !uiState.isRunningInspection,
                                onClick = { onSamplePresetSelected(preset) }
                            )
                            if (index < uiState.samplePresets.lastIndex) {
                                HairlineDivider()
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(BharatSpacing.md))
            }
        } else {
            item {
                TonalNoticeBanner(
                    message = "Sample presets are only available for PCB Board demo mode. Visual anomaly inspection requires real camera capture or photo upload with an imported, ready PatchCore model.",
                    icon = Icons.Outlined.Info,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("inspect_transistor_sample_notice")
                )
                Spacer(modifier = Modifier.height(BharatSpacing.md))
            }
        }
    }
}

@Composable
private fun InspectionInputAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = BharatSpacing.xs, vertical = BharatSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(21.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PcbPreviewViewport(
    uiState: InspectUiState,
    allocateCameraCaptureDestination: suspend () -> Pair<String, Uri>,
    onCameraImageCaptured: (String, Boolean) -> Unit,
    onCameraCaptureError: (String) -> Unit,
    onCancelLiveCamera: () -> Unit,
    onUseSystemCameraFallback: () -> Unit,
    onClearSelectedImage: () -> Unit
) {
    val isTransistor = uiState.selectedTarget == InspectionTarget.TRANSISTOR

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inspect_preview_viewport"),
        verticalArrangement = Arrangement.spacedBy(BharatSpacing.sm)
    ) {
        when {
            uiState.isCameraLiveActive -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CameraXLiveCapturePane(
                        allocateDestination = allocateCameraCaptureDestination,
                        onCaptured = onCameraImageCaptured,
                        onCameraError = onCameraCaptureError,
                        onCancel = onCancelLiveCamera,
                        onUseSystemCameraFallback = onUseSystemCameraFallback
                    )
                }
            }

            uiState.selectedImageUri != null -> {
                val valResult = uiState.validation
                val ratio = if (valResult != null && valResult.width > 0 && valResult.height > 0) {
                    (valResult.width.toFloat() / valResult.height.toFloat()).coerceIn(0.65f, 1.85f)
                } else {
                    4f / 3f
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = Uri.parse(uiState.selectedImageUri),
                        contentDescription = uiState.boardName.ifBlank {
                            if (isTransistor) "Selected component preview" else "Selected PCB preview"
                        },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    IconButton(
                        onClick = onClearSelectedImage,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(BharatSpacing.sm)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                            .testTag("inspect_clear_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = if (isTransistor) "Clear selected image" else "Clear selected PCB image",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Clean open resolution & clarity bar directly below image
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dims = if (valResult != null && valResult.width > 0) {
                        "${valResult.width} × ${valResult.height} px"
                    } else {
                        "Validating dimensions..."
                    }
                    Text(
                        text = dims,
                        style = MonoTextStyles.metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(BharatSpacing.xs)) {
                        if (uiState.selectedSourceType == PcbImageSource.SAMPLE) {
                            DemonstrationBadge(label = "SAMPLE DEMO")
                        }
                        if (valResult != null && valResult.isProcessable) {
                            ClarityStatusBadge(lowClarityRisk = valResult.lowClarityRisk)
                        }
                    }
                }
            }

            else -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 216.dp)
                            .padding(BharatSpacing.section),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CenterFocusWeak,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.height(BharatSpacing.md))
                        Text(
                            text = if (isTransistor) "No component image selected" else "No PCB image selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(BharatSpacing.xs))
                        Text(
                            text = if (isTransistor) {
                                "Capture a component photo or upload an image from gallery."
                            } else {
                                "Capture a board photo, upload from gallery, or load a sample PCB below."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraXLiveCapturePane(
    allocateDestination: suspend () -> Pair<String, Uri>,
    onCaptured: (String, Boolean) -> Unit,
    onCameraError: (String) -> Unit,
    onCancel: () -> Unit,
    onUseSystemCameraFallback: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var bindError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                boundProvider = provider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                imageCapture = capture
            } catch (e: Throwable) {
                Log.w("CameraXLive", "CameraX bind failed", e)
                bindError = e.message ?: "Live camera unavailable on this device."
            }
        }, executor)

        onDispose {
            runCatching { boundProvider?.unbindAll() }
        }
    }

    Column(modifier = Modifier.padding(BharatSpacing.md)) {
        if (bindError != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BharatSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Live CameraX preview unavailable",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = bindError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Close")
                    }
                    Button(
                        onClick = onUseSystemCameraFallback,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("System Camera")
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(BharatSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val capture = imageCapture ?: return@Button
                        if (isCapturing) return@Button
                        isCapturing = true
                        coroutineScope.launch {
                            val (fileUriString, _) = allocateDestination()
                            val destFile = File(Uri.parse(fileUriString).path ?: "")
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(destFile).build()
                            capture.takePicture(
                                outputOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        isCapturing = false
                                        onCaptured(fileUriString, true)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        isCapturing = false
                                        onCameraError("Capture failed: ${exception.message ?: "camera error"}")
                                        onCaptured(fileUriString, false)
                                    }
                                }
                            )
                        }
                    },
                    enabled = imageCapture != null && !isCapturing,
                    shape = MaterialTheme.shapes.medium,
                    elevation = null,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(if (isCapturing) "Capturing..." else "Snap Frame")
                }
            }
        }
    }
}

@Composable
private fun SamplePresetRow(
    preset: SamplePcbPreset,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val drawableId = remember(preset.drawableName) {
        SamplePcbCatalog.drawableResId(preset.drawableName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = BharatSpacing.md)
            .testTag("sample_preset_${preset.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (drawableId != 0) {
            Image(
                painter = painterResource(id = drawableId),
                contentDescription = preset.boardName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.CenterFocusWeak,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        Spacer(modifier = Modifier.width(BharatSpacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = preset.boardName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preset.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preset.orientationLabel,
                style = MonoTextStyles.compact,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "Load sample",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

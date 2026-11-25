#!/usr/bin/env python3
"""
On-Device ExecuTorch Llama Advisory Engine & SQLite Inspection Logger
Supports both:
  1. Optimum ExecuTorch Python Runtime (macOS / Linux dev & benchmarking)
  2. Compiled C++ ExecuTorch llama_main binary (Raspberry Pi 3 Model B+ / Arm Linux)
"""

import json
import re
import sqlite3
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional

DEFECT_SOP_KB: Dict[str, Dict[str, str]] = {
    # --- 11 Unified Supervised Classes (DeepPCB + DSCPCBD + NEU + PCB Ind) ---
    "open": {
        "domain": "PCB Conductive Trace",
        "standard": "IPC-A-610 Section 10.1",
        "default_severity": "CRITICAL",
        "default_disposition": "SCRAP",
        "rule": "Complete discontinuity in conductive trace breaks electrical connectivity.",
        "action": "Scrap or jumper-rework board; inspect etching bath and photomask alignment."
    },
    "short": {
        "domain": "PCB Conductive Trace",
        "standard": "IPC-A-610 Section 10.1",
        "default_severity": "CRITICAL",
        "default_disposition": "HALT_LINE",
        "rule": "Unintended copper bridge violates minimum electrical clearance and risks board failure.",
        "action": "Halt solder/etch station; inspect stencil alignment and reflow thermal profile."
    },
    "mouse_bite": {
        "domain": "PCB Trace Edge",
        "standard": "IPC-A-610 Section 10.1.3",
        "default_severity": "MEDIUM",
        "default_disposition": "REWORK",
        "rule": "Acceptable only if conductor width reduction is under 20% of nominal trace width.",
        "action": "Measure remaining trace width; inspect depaneling router bit wear."
    },
    "spur": {
        "domain": "PCB Trace Protrusion",
        "standard": "IPC-A-610 Section 10.1.2",
        "default_severity": "MEDIUM",
        "default_disposition": "REWORK",
        "rule": "Reject if copper protrusion reduces minimum conductor spacing below spec.",
        "action": "Trim excess copper spur; check developer spray pressure and line speed."
    },
    "spurious_copper": {
        "domain": "PCB Substrate Surface",
        "standard": "IPC-A-610 Section 10.2",
        "default_severity": "MEDIUM",
        "default_disposition": "REWORK",
        "rule": "Isolated residual copper islands risk bridging adjacent nets.",
        "action": "Remove stray copper flake; inspect resist stripping tank filtration."
    },
    "missing_copper": {
        "domain": "PCB Pad / Plane",
        "standard": "IPC-A-610 Section 10.1",
        "default_severity": "CRITICAL",
        "default_disposition": "SCRAP",
        "rule": "Missing pad or ground-plane copper prevents reliable solder fillet formation.",
        "action": "Quarantine board; check copper foil lamination and over-etching timer."
    },
    "copper_burr": {
        "domain": "PCB Drilled Via / Edge",
        "standard": "IPC-A-610 Section 10.3",
        "default_severity": "LOW",
        "default_disposition": "REWORK",
        "rule": "Raised metallic burr can puncture solder mask or cause assembly shorts.",
        "action": "Deburr board surface; replace worn carbide drill bits on CNC station."
    },
    "pin_hole": {
        "domain": "PCB Pad / Solder Joint",
        "standard": "IPC-A-610 Section 5.2",
        "default_severity": "LOW",
        "default_disposition": "REWORK",
        "rule": "Small void in copper/solder acceptable if structural and electrical area remain >=80%.",
        "action": "Touch up joint; verify board pre-bake moisture removal cycle."
    },
    "hole_breakout": {
        "domain": "PCB Via / Through-Hole",
        "standard": "IPC-A-610 Section 10.3.2",
        "default_severity": "CRITICAL",
        "default_disposition": "SCRAP",
        "rule": "Drilled hole breaks outside annular ring boundary, compromising barrel integrity.",
        "action": "Scrap panel; recalibrate CNC drill registration fiducials immediately."
    },
    "scratch": {
        "domain": "Metal / Surface Finish (NEU / PCB)",
        "standard": "ISO 9443 / IPC-A-610",
        "default_severity": "MEDIUM",
        "default_disposition": "REWORK",
        "rule": "Surface abrasion exposes bare metal to oxidation or creates mechanical stress risers.",
        "action": "Apply protective overcoat if shallow; inspect conveyor guide rails for debris."
    },
    "copper": {
        "domain": "Exposed Base Copper",
        "standard": "IPC-A-610 Section 10.4",
        "default_severity": "LOW",
        "default_disposition": "REWORK",
        "rule": "Exposed bare copper outside solderable lands requires solder-mask touch-up.",
        "action": "Touch up with UV-curable solder mask; check solder mask curtain coating viscosity."
    },

    # --- 4 Unsupervised MVTec AD Transistor Anomaly Classes ---
    "bent_lead": {
        "domain": "Discrete Transistor Assembly",
        "standard": "IPC-A-610 Section 8.3",
        "default_severity": "MEDIUM",
        "default_disposition": "REWORK",
        "rule": "Lead coplanarity deviation exceeds 0.1 mm pitch tolerance for THT/SMD insertion.",
        "action": "Re-form leads with precision jig; inspect feeder tube and pick-and-place nozzle."
    },
    "cut_lead": {
        "domain": "Discrete Transistor Assembly",
        "standard": "IPC-A-610 Section 8.3",
        "default_severity": "CRITICAL",
        "default_disposition": "SCRAP",
        "rule": "Truncated terminal lead prevents mechanical retention and electrical contact.",
        "action": "Reject component immediately; inspect lead-forming shear die clearance."
    },
    "damaged_case": {
        "domain": "Discrete Transistor Package",
        "standard": "IPC-A-610 Section 9.1",
        "default_severity": "CRITICAL",
        "default_disposition": "SCRAP",
        "rule": "Cracked or chipped epoxy package exposes semiconductor die to moisture ingress.",
        "action": "Scrap component; reduce placement head Z-axis impact force."
    },
    "misplaced": {
        "domain": "Discrete Transistor Placement",
        "standard": "IPC-A-610 Section 8.2",
        "default_severity": "MEDIUM",
        "default_disposition": "REWORK",
        "rule": "Component rotational or XY offset exceeds 25% of pad width.",
        "action": "Re-seat component prior to reflow; recalibrate vision fiducial alignment."
    },
    "transistor_anomaly": {
        "domain": "Unsupervised PatchCore Anomaly",
        "standard": "IPC-A-610 / Golden Sample Baseline",
        "default_severity": "MEDIUM",
        "default_disposition": "REWORK",
        "rule": "Pixel-level feature distance exceeded PatchCore coreset anomaly threshold.",
        "action": "Quarantine transistor for optical inspection and verify feeder alignment."
    },
}

VALID_SEVERITIES = {"LOW", "MEDIUM", "CRITICAL"}
VALID_DISPOSITIONS = {"PASS", "REWORK", "SCRAP", "HALT_LINE"}


@dataclass
class DetectionEvent:
    component_id: str
    pipeline: str
    defect_class: str
    score: float
    area_pct: float
    bbox: Optional[list] = None


class ExecuTorchAdvisoryEngine:
    def __init__(
        self,
        model_dir: str = "models/smollm2_360m",
        backend: str = "optimum",
        cpp_runner_bin: Optional[str] = None,
        db_path: str = "inspection_logs.db",
        max_seq_len: int = 256,
    ):
        self.model_dir = Path(model_dir).resolve()
        self.backend = backend
        self.cpp_runner_bin = str(Path(cpp_runner_bin).resolve()) if cpp_runner_bin else None
        self.db_path = db_path
        self.max_seq_len = max_seq_len

        self._model = None
        self._tokenizer = None
        self._init_db()

        if self.backend == "optimum":
            self._load_optimum_runtime()

    def _init_db(self) -> None:
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS inspection_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp REAL NOT NULL,
                    component_id TEXT NOT NULL,
                    pipeline TEXT NOT NULL,
                    defect_class TEXT NOT NULL,
                    score REAL NOT NULL,
                    area_pct REAL NOT NULL,
                    severity TEXT NOT NULL,
                    disposition TEXT NOT NULL,
                    standard TEXT NOT NULL,
                    root_cause TEXT NOT NULL,
                    operator_action TEXT NOT NULL,
                    llm_latency_ms REAL NOT NULL
                )
            """)

    def _load_optimum_runtime(self) -> None:
        from optimum.executorch import ExecuTorchModelForCausalLM
        from transformers import AutoTokenizer

        self._model = ExecuTorchModelForCausalLM.from_pretrained(str(self.model_dir))
        self._tokenizer = AutoTokenizer.from_pretrained(str(self.model_dir))

    def _build_prompt(self, event: DetectionEvent, sop: Dict[str, str]) -> str:
        system_prompt = (
            "You are an offline factory QC AI. Output ONLY valid JSON with exact lowercase keys: "
            '{"severity": "LOW|MEDIUM|CRITICAL", "disposition": "PASS|REWORK|SCRAP|HALT_LINE", '
            '"root_cause": "<short cause>", "operator_action": "<short action>"}.'
        )
        example_user = "Defect: short | Score: 0.92 | Area: 2.5% | Rule: Copper bridge violates clearance."
        example_assistant = (
            '{"severity": "CRITICAL", "disposition": "HALT_LINE", '
            '"root_cause": "Solder bridge across adjacent traces", '
            '"operator_action": "Halt line and inspect solder stencil alignment"}'
        )
        user_query = (
            f"Defect: {event.defect_class} | Pipeline: {event.pipeline} | "
            f"Score: {event.score:.2f} | Area: {event.area_pct:.1f}% | "
            f"Standard: {sop['standard']} | Rule: {sop['rule']}"
        )

        return (
            f"<|im_start|>system\n{system_prompt}<|im_end|>\n"
            f"<|im_start|>user\n{example_user}<|im_end|>\n"
            f"<|im_start|>assistant\n{example_assistant}<|im_end|>\n"
            f"<|im_start|>user\n{user_query}<|im_end|>\n"
            f"<|im_start|>assistant\n"
        )

    def _run_inference(self, prompt: str) -> str:
        if self.backend == "optimum":
            return self._model.text_generation(
                tokenizer=self._tokenizer,
                prompt=prompt,
                max_seq_len=self.max_seq_len,
            )

        if self.backend == "cpp_runner":
            if not self.cpp_runner_bin:
                raise ValueError("cpp_runner_bin must be provided when backend='cpp_runner'")
            cmd = [
                self.cpp_runner_bin,
                "--model_path", str(self.model_dir / "model.pte"),
                "--tokenizer_path", str(self.model_dir / "tokenizer.json"),
                "--seq_len", str(self.max_seq_len),
                "--temperature", "0.1",
                "--prompt", prompt,
            ]
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=20, check=False)
            return res.stdout

        raise ValueError(f"Unsupported backend: {self.backend}")

    def _parse_and_validate(
        self, raw_text: str, event: DetectionEvent, sop: Dict[str, str]
    ) -> Dict[str, str]:
        turn = raw_text.split("<|im_start|>assistant\n")[-1]
        turn = turn.split("<|im_end|>")[0].strip()

        parsed_norm: Dict[str, Any] = {}
        match = re.search(r"\{.*\}", turn, re.DOTALL)
        if match:
            try:
                raw_json = json.loads(match.group(0))
                parsed_norm = {str(k).strip().lower(): v for k, v in raw_json.items()}
            except json.JSONDecodeError:
                pass

        severity = str(parsed_norm.get("severity", "")).upper().strip()
        if severity not in VALID_SEVERITIES:
            severity = sop["default_severity"]

        disposition = str(parsed_norm.get("disposition", "")).upper().strip()
        if disposition not in VALID_DISPOSITIONS:
            disposition = sop["default_disposition"]

        if event.score >= 0.88 and event.area_pct >= 4.0 and severity == "MEDIUM":
            severity = "CRITICAL"
            if disposition == "REWORK":
                disposition = "SCRAP"

        root_cause = str(parsed_norm.get("root_cause", "")).strip() or sop["rule"]
        operator_action = str(parsed_norm.get("operator_action", "")).strip() or sop["action"]

        return {
            "severity": severity,
            "disposition": disposition,
            "standard": sop["standard"],
            "root_cause": root_cause,
            "operator_action": operator_action,
        }

    def advise(self, event: DetectionEvent) -> Dict[str, Any]:
        key = event.defect_class.lower().strip()
        sop = DEFECT_SOP_KB.get(
            key,
            {
                "domain": "Manufacturing Surface",
                "standard": "IPC-A-610 / ISO-9443",
                "default_severity": "MEDIUM",
                "default_disposition": "REWORK",
                "rule": "Detected surface deviation beyond baseline tolerance.",
                "action": "Quarantine part for manual QC verification.",
            },
        )

        prompt = self._build_prompt(event, sop)
        t0 = time.perf_counter()
        raw_output = self._run_inference(prompt)
        latency_ms = round((time.perf_counter() - t0) * 1000.0, 2)

        structured = self._parse_and_validate(raw_output, event, sop)
        record = {
            "timestamp": time.time(),
            "component_id": event.component_id,
            "pipeline": event.pipeline,
            "defect_class": key,
            "score": round(event.score, 4),
            "area_pct": round(event.area_pct, 2),
            **structured,
            "llm_latency_ms": latency_ms,
        }

        self._log_to_sqlite(record)
        return record

    def _log_to_sqlite(self, rec: Dict[str, Any]) -> None:
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                """
                INSERT INTO inspection_logs (
                    timestamp, component_id, pipeline, defect_class, score, area_pct,
                    severity, disposition, standard, root_cause, operator_action, llm_latency_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    rec["timestamp"],
                    rec["component_id"],
                    rec["pipeline"],
                    rec["defect_class"],
                    rec["score"],
                    rec["area_pct"],
                    rec["severity"],
                    rec["disposition"],
                    rec["standard"],
                    rec["root_cause"],
                    rec["operator_action"],
                    rec["llm_latency_ms"],
                ),
            )


if __name__ == "__main__":
    engine = ExecuTorchAdvisoryEngine(model_dir="models/smollm2_360m", backend="optimum")

    sample_events = [
        DetectionEvent("PCB-001", "supervised_yolo", "short", 0.91, 3.2, [120, 88, 165, 130]),
        DetectionEvent("PCB-002", "supervised_yolo", "mouse_bite", 0.84, 0.9, [310, 200, 332, 218]),
        DetectionEvent("TRN-014", "unsupervised_patchcore", "bent_lead", 0.89, 4.5, [95, 140, 180, 240]),
    ]

    for ev in sample_events:
        result = engine.advise(ev)
        print(json.dumps(result, indent=2))

# Audio-Visual Zooming Android Application
### IEEE Signal Processing Cup 2026

An Android application developed in **Kotlin** for synchronized **stereo audio and video capture**, real-time **audio signal processing**, and **on-device inference** for audio-visual zooming experiments.

The application was developed as part of our participation in the **IEEE Signal Processing Cup 2026**, which focuses on real-time audio-visual zooming on smartphones under edge-computing constraints.

---

## Features

- Simultaneous stereo audio and video recording
- Real-time stereo channel (Left/Right) extraction
- Configurable audio acquisition pipeline (16 kHz sampling)
- Camera zoom tracking and metadata logging
- Short-Time Fourier Transform (STFT) preprocessing
- Audio feature extraction for model inference
- ONNX Runtime integration for on-device inference
- Inverse STFT reconstruction and enhanced audio generation
- Export of recorded WAV files and processed outputs

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Camera:** CameraX
- **Audio API:** AudioRecord
- **ML Runtime:** ONNX Runtime Android
- **FFT Library:** JTransforms
- **Platform:** Android

---

## Project Workflow

```
Camera + Stereo Microphone
            │
            ▼
Synchronized Audio & Video Capture
            │
            ▼
Stereo Channel Separation
            │
            ▼
Short-Time Fourier Transform (STFT)
            │
            ▼
Feature Extraction
(LPS, IPD, Directional Features)
            │
            ▼
ONNX Runtime Inference
            │
            ▼
Enhanced Spectrogram
            │
            ▼
Inverse STFT
            │
            ▼
Enhanced Audio Output (.wav)
```

---

## Modules

### 1. Stereo Recorder

- Captures stereo audio using Android's `AudioRecord`
- Separates left and right channels
- Stores:
  - `left.wav`
  - `right.wav`
  - `mix.wav`

---

### 2. Camera Module

- Video recording using CameraX
- Pinch-to-zoom support
- Continuous zoom ratio logging
- Saves synchronized metadata in JSON format

---

### 3. Signal Processing

Performs:

- Hann window generation
- 512-point STFT
- Spectrogram computation
- Inverse STFT reconstruction

---

### 4. Feature Extraction

Computes model input features including:

- Log Power Spectrum (LPS)
- Cosine Inter-channel Phase Difference
- Sine Inter-channel Phase Difference
- Directional Feature (Inside Beam)
- Directional Feature (Outside Beam)

These features are formatted for inference on the deployed neural network.

---

### 5. Inference Engine

- Loads ONNX model on-device
- Converts extracted features into tensors
- Executes inference using ONNX Runtime
- Produces enhanced spectrogram estimates
- Reconstructs enhanced audio signal

---

## Output Structure

```
Documents/
    ieee/
        <session_timestamp>/
            video.mp4
            left.wav
            right.wav
            mix.wav
            zoom_logs.json

Music/
    enhanced_<timestamp>.wav
```

---

## Key Contributions

- Developed an Android application for synchronized stereo audio and video acquisition.
- Implemented real-time audio preprocessing and feature extraction pipeline.
- Integrated ONNX Runtime for low-latency on-device inference.
- Designed metadata logging to synchronize camera zoom information with recorded multimedia.

---

## Note

The machine learning model architecture and training were developed by the research team. This application focuses on **data acquisition, signal processing, Android deployment, and inference integration** for edge-device execution.

---

## Competition

**IEEE Signal Processing Cup 2026**

Theme:
> *AV Zoom: Real-Time Audio-Visual Zooming on Smartphones*

The challenge emphasizes low-latency, on-device audio-visual processing for intelligent smartphone applications under constrained computational resources.

Our team successfully advanced through the initial evaluation rounds and was selected as one of the **three finalist (semi-finalist) teams**, demonstrating the effectiveness of our real-time audio-visual zooming solution under edge-computing constraints.

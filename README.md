# Barbell Path Tracking App

This repository contains a **demo Android application** and **backend implementation** for an object tracking system designed to detect and track barbells in weightlifting videos.  
It serves as a foundation for a future app build that will use this video processing implementation.

## Overview

The app demonstrates how object detection and tracking can be integrated into a mobile application.  
It uses a **YOLOv8n model** for detection and integrates a **SORT tracking algorithm** to maintain object identity across frames.  
The model runs on-device with **GPU delegate support**, ensuring smooth performance during processing.

## Current Features

- Pre-recorded video processing with YOLOv8n detection  
- SORT-based object tracking  
- GPU delegate for optimized performance  
- Manual barbell selection before processing  
- Visualization of barbell motion using bounding boxes and trajectory paths  

## Development Status

This project is **currently in active development**.  
The existing version demonstrates the core video processing pipeline, while the next iterations will focus on expanding configurability and improving robustness.

## Future Plans

- **Video ratio flexibility:** enable processing of different aspect ratios (currently optimized for 1:1 videos)  
- **Model configuration:** allow selection between multiple YOLO models depending on accuracy/performance trade-offs  
- **Additional analytics:** include metrics such as repetition count, range of motion, and velocity tracking  
- **Improved redundancy:** make the backend more fault-tolerant and modular  

## Requirements

- **Android Studio (latest version)**
- **Kotlin**
- **OpenCV for Android**
- **TensorFlow Lite** with GPU delegate support
- **YOLOv8n TFLite model**

## Notes

This repository represents a **proof of concept** for video-based motion tracking.  
It will later evolve into a full-fledged barbell tracking and training analytics application.



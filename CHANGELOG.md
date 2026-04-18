# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.2.0-M3] - 2026-04-18

### Added

- Shared community CI workflow for build verification across branches and pull requests.
- Desktop launcher support for downloading, reviewing, copying, retrying, and deleting Ollama models from a dedicated model manager window.
- Desktop launcher guidance screenshots covering the config editor, startup flow, and Ollama model manager.

### Changed

- Expanded the desktop launcher documentation in the README and getting started guide, including platform-specific install notes and launcher walkthroughs.
- Improved launcher setup UX with clearer Ollama status information, installed-model visibility, and a more capable configuration flow.
- Limited desktop and container release workflows to `main` pushes and version tags, while keeping the general CI workflow available on every branch.

### Fixed

- Corrected invalid launcher config template YAML structure for Spring AI settings.
- Improved Electron launcher startup and splash behavior, including more consistent Spring server shutdown handling across platforms.

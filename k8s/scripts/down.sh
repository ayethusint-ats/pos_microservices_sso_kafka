#!/usr/bin/env bash
# Phase 16: delete the local kind cluster (all pods, volumes and data in it). Compose data is untouched.
set -euo pipefail
kind delete cluster --name banking

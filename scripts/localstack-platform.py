#!/usr/bin/env python3
"""Starts, stops, or restarts an emulator platform via cloudforge-cli.

    python3 scripts/localstack-platform.py localstack restart

A thin wrapper around `cloudforge-cli emulator <action> --target <platform>`, kept as its own
module so compliance-deploy-runner.py can import it and call restart_clean() directly. Requires
cloudforge-cli on PATH.
"""
import json
import subprocess
import sys
import time
import urllib.request

HEALTH_URL = "http://localhost:4566/_localstack/health"


def control(platform, action):
    result = subprocess.run(["cloudforge-cli", "emulator", action, "--target", platform],
                             capture_output=True, text=True, timeout=180)
    return result.stdout + result.stderr


def wait_healthy(timeout=180):
    """True once LocalStack reports CloudFormation available or running."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(HEALTH_URL, timeout=5) as response:
                services = json.loads(response.read()).get("services", {})
                if services.get("cloudformation") in ("available", "running"):
                    return True
        except (OSError, ValueError):
            pass
        time.sleep(4)
    return False


def restart_clean():
    """Recreates the LocalStack container so the next deployment starts from empty state."""
    control("localstack", "restart")
    return wait_healthy()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print(control(sys.argv[1], sys.argv[2])[-800:])

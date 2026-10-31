#!/usr/bin/env python3
"""Ensure app/google-services.json exists and is valid JSON.

CI secrets may be missing; the google-services Gradle plugin fails with
"Malformed root json" when the file is empty. Write a local placeholder that
matches the app's applicationIds so debug/release builds can proceed.
"""
import json
import pathlib
import sys

PLACEHOLDER = {
    "project_info": {
        "project_number": "000000000000",
        "project_id": "liquidhub-local-build",
        "storage_bucket": "liquidhub-local-build.appspot.com",
    },
    "client": [
        {
            "client_info": {
                "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
                "android_client_info": {"package_name": "LiquidHub.teto.x"},
            },
            "oauth_client": [],
            "api_key": [{"current_key": "AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}],
            "services": {"appinvite_service": {"other_platform_oauth_client": []}},
        },
        {
            "client_info": {
                "mobilesdk_app_id": "1:000000000000:android:1111111111111111",
                "android_client_info": {"package_name": "LiquidHub.teto.x.debug"},
            },
            "oauth_client": [],
            "api_key": [{"current_key": "AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}],
            "services": {"appinvite_service": {"other_platform_oauth_client": []}},
        },
    ],
    "configuration_version": "1",
}


def main() -> int:
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/google-services.json")
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        json.loads(path.read_text())
        print(f"{path} is valid")
        return 0
    except Exception:
        path.write_text(json.dumps(PLACEHOLDER, indent=2) + "\n")
        print(f"{path} was missing or invalid; wrote placeholder")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

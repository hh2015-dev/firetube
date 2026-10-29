#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Read public official docs through the inherited proxy/TLS configuration; no login."""
import concurrent.futures
import datetime
import json
import urllib.request

URLS = (
    "https://developers.google.com/identity/authorization/android",
    "https://developers.google.com/identity/oauth2/web/guides/use-token-model",
    "https://developers.google.com/workspace/drive/api/guides/api-specific-auth",
    "https://developers.google.com/workspace/drive/api/guides/manage-downloads",
    "https://developers.google.com/workspace/drive/picker/guides/overview",
    "https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification",
    "https://developers.google.com/terms/api-services-user-data-policy",
    "https://developers.google.com/workspace/drive/api/guides/limits",
    "https://support.google.com/cloud/answer/9110914",
    "https://developer.android.com/training/data-storage/shared/documents-files",
)


def check(url):
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            return {"url": url, "status": response.status, "final_url": response.url, "bytes": len(response.read())}
    except Exception as error:
        return {"url": url, "error": str(error)}


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        print(json.dumps({"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          "results": list(executor.map(check, URLS))}, indent=2))

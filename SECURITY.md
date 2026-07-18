# Security Policy

## Supported versions

Security fixes are provided for the latest published Loya release. Older builds may no longer receive fixes, so users should update from the repository's latest GitHub Release.

## Report a vulnerability privately

Please do not disclose a suspected vulnerability in a public issue, discussion, pull request, screenshot, or social-media post.

Use GitHub's private vulnerability reporting:

1. Open this repository on GitHub.
2. Select the **Security** tab.
3. Select **Advisories** and then **Report a vulnerability**.
4. Describe the problem, the affected edition and version, reproduction steps, and the security impact.

Do not attach real identity documents, payment cards, loyalty cards, tickets, QR codes, barcodes, signing keys, passwords, or API credentials. Use invented test data and redact screenshots.

If **Report a vulnerability** is not available, open a public issue containing only a request for the maintainers to enable private vulnerability reporting. Do not include vulnerability details in that issue.

## What to include

- Affected edition: Offline APK, Google APK, or PWA.
- App version and Android/browser version.
- Clear reproduction steps using non-sensitive test data.
- Expected and actual behavior.
- Potential impact and any suggested mitigation.
- Whether the issue is already being exploited or publicly discussed.

## Scope

Useful reports include unauthorized access to locally stored wallet data, unsafe backup import or export handling, WebView or PWA origin problems, permission bypasses, signing or update-chain weaknesses, dependency vulnerabilities, and accidental network transmission of wallet content.

Reports about upstream Android, browser, Google Play services, ML Kit, ZXing, OpenCV, or JavaScript-library behavior may need to be coordinated with the relevant upstream project. Please still report a Loya integration problem privately when the application can mitigate it.

## Safe research

Only test devices, accounts, and data that you own or are explicitly authorized to use. Do not access another person's documents or disrupt services. Good-faith reports that follow this policy will be handled on a best-effort basis.

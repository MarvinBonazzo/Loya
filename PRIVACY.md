# Privacy and Local Data

Loya is designed to keep cards, tickets, codes, notes, and document images under the user's control. It has no developer-operated account system or wallet-data backend. The three editions do not have identical storage and network behavior, so read the section for the edition you use.

## Quick comparison

| Edition | Recognition and network behavior | Wallet storage |
| --- | --- | --- |
| Offline APK | ZXing and OpenCV run locally. The app is built without Internet permission. | Android private app storage on the device. |
| Google APK | A bundled ML Kit barcode model runs on-device. The document-scanner components are delivered by Google Play services and the document-scanner flow runs on-device. Google Play services needs network access to obtain those components when they are not already installed. | Android private app storage on the device. |
| PWA | Static application files are downloaded from the hosting site. Camera recognition runs in the browser. The host receives ordinary web-request metadata when the PWA is loaded or updated. | Wallet records and compressed images in `localStorage`; static offline files in Cache Storage, all belonging to the PWA's web origin. |

## Offline APK

The Offline APK uses ZXing for barcode recognition and OpenCV for document processing. These operations are performed on the device. The packaged Offline APK does not request Android's Internet permission and does not include advertising, analytics, telemetry, Firebase, or a Loya cloud service.

The app may request camera access when the user starts a scan. Card metadata is saved in private application preferences and document images are saved as private application files. Android's application sandbox prevents ordinary access by other apps, but Loya does not currently add a separate user password or its own full database/file encryption layer.

## Google APK

The Google APK uses these Google components:

- `com.google.mlkit:barcode-scanning:17.3.0`: the barcode model is bundled into the APK and is available immediately for on-device recognition.
- `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`: the document-scanner models, scanning logic, and user interface are delivered dynamically by Google Play services. According to Google's documentation, the entire document-scanner flow operates on-device.

The Google APK does not add a Loya account, Firebase Analytics, advertising, or a developer-operated upload service. It does, however, depend on Google Play services. Google Play services may connect to Google to download or update scanner components and may handle operational or diagnostic information according to the device's Google settings and Google's own terms and privacy policy. This edition is therefore not the same as the strictly no-Google Offline APK.

Wallet records and returned document images are stored in the same private Android app storage model described for the Offline APK. A Google account is not required by Loya itself, but compatible and sufficiently recent Google Play services are required for the Google document scanner.

## PWA

The PWA is a static web application. Loading or updating it contacts the site that hosts its HTML, JavaScript, styles, icons, and service worker. As with any website, the hosting provider may receive ordinary request information such as IP address, browser type, requested files, and timestamps. Loya does not intentionally send saved wallet content to the host and adds no wallet API, account, analytics, advertising, cloud-sync, CDN, or telemetry call.

Cards, codes, notes, and compressed imported images are stored in browser `localStorage` under the PWA's origin. The current PWA does not use IndexedDB. Its service worker uses Cache Storage for the same-origin static application shell and same-origin runtime `GET` responses; it ignores cross-origin requests. Cache Storage does not contain the wallet database.

The service worker allows the cached application shell to open offline after it has been loaded successfully at least once. A network connection is still needed for the first load and for future application updates.

Wallet data is not automatically synchronized to the Android APKs or to another browser. `localStorage` has browser-dependent size limits and is not a substitute for an encrypted backup. Any script served from the same web origin shares that origin's security boundary. For sensitive documents, a dedicated origin that hosts no unrelated applications is safer than a shared `username.github.io` origin.

Camera scanning requires an HTTPS origin and browser camera permission. Browser and device support varies; some browsers may fall back to manual entry or image import.

## Backups, deletion, and data loss

Loya intentionally disables automatic Android cloud backup and automatic device-to-device transfer for wallet files. Instead, every edition provides a manual **Create backup** action. It writes one portable JSON file containing the wallet records and JPEG, PNG, or WebP images encoded inside the file. The same file can be restored in another Loya APK or PWA installation by choosing **Restore backup** and explicitly confirming replacement of the current wallet.

The backup is **not encrypted**. Anyone who obtains it can read the saved codes, notes, and images. Loya itself does not upload the backup, but the Android system file picker or the browser download location may let the user choose a third-party cloud folder. If the user chooses one, that provider receives and stores the file under its own terms. Keep backups in a private location and delete unwanted copies.

Restore validates the file type, structure, size, identifiers, supported formats, and image signatures before replacement. It is a replace-or-cancel operation, not a merge. Existing wallet data is left unchanged when validation or storage fails, but a successful confirmed restore replaces the current wallet.

There is still no automatic synchronization or developer-operated recovery service. Important consequences are:

- Uninstalling an APK, clearing its app data, or losing/resetting the phone deletes the live wallet. Create a current backup first and keep that file somewhere safe.
- Installing an APK signed by a different key cannot update the existing app. Uninstalling the existing app to work around a signature mismatch deletes its private data.
- The Offline and Google APKs currently share the package name `com.cartawallet.app`, so they cannot be installed side by side. A correctly signed update from one edition to the other can retain the existing private wallet; both APKs must use the same stable release signing key. If Android rejects the update, do not uninstall the existing edition until you have created and safely copied a Loya backup.
- Clearing browser site data, using private browsing, browser storage eviction, changing browsers, or moving the PWA to a different origin can delete or hide PWA data.
- Removing one saved wallet item deletes its app-managed record and associated app-managed images. Storage systems may retain low-level forensic remnants until the operating system reuses the space.

Do not keep the only irreplaceable copy of an identity document or ticket in Loya.

## Permissions

- Camera: used only when the user chooses a scanning feature that needs it.
- Vibration: used for scan feedback.
- Storage or photo access: requested through system pickers when the user chooses an existing image; broad shared-storage access is not required by the current design.
- Internet: absent from the Offline APK. The Google APK relies on Google Play services for delivery of its document-scanner components. The PWA necessarily uses the network to load and update static application files.

## No sale of data

Loya does not sell wallet data. The project contains no advertising SDK and no developer-operated behavioral profiling system.

## Security reports

Do not include real documents or codes in a public issue. Follow [SECURITY.md](SECURITY.md) and use GitHub's private vulnerability-reporting flow.

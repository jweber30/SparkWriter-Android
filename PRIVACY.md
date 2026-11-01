# SparkWriter Privacy Policy

Effective: November 1, 2026

SparkWriter is an Android app that turns an operating-system image and your configuration into bootable USB installation media. This policy describes what the app does with your information.

## Summary

SparkWriter does not collect, transmit, or share your personal information. There are no accounts, no analytics, no advertising, and no crash reporting. The developer operates no server that receives data from the app and holds no data about you.

## Data stored on your device

SparkWriter keeps the following in its private app storage. It never leaves your device through the app.

- **Saved profile values** you choose to keep: username, email, SSH public keys, network suffix, Wi-Fi network name (SSID), timezone, and keyboard layout. Passwords are never saved.
- **Installed SparkPlugs** (installer packages) and the address each was installed from.
- **Write history:** a record of each USB write or image export, with the image checksum, size, and result.
- **An index of image fingerprints** used to recognize images you have imported before.
- **Image files** you downloaded or imported, and temporary files created while preparing or writing an image.
- **Your settings**, such as whether local ZIP import is enabled.

Passwords and other secrets generated for an installer are written into the installation image and shown to you. They are removed from the app's working files when the write finishes.

SparkWriter turns off Android cloud backup for its data.

## Network activity

SparkWriter connects to the internet only when you act:

- **Installing a SparkPlug** from a web address or `spark://` link. The app downloads the package files from that address. The host of that address can see your IP address and standard request details, as with any web request.
- **Opening a download or torrent link** for an operating-system image. The app hands the link to your browser or torrent app. SparkWriter does not download the image itself, and the site you visit has its own privacy practices.

SparkWriter does not contact any other service, and it sends none of your saved values to anyone.

## Permissions

- **Internet:** to install SparkPlugs as described above.
- **Notifications and foreground service:** to show progress while a USB write is running.
- **USB access:** granted by Android, per device, when you choose a drive to write.

SparkWriter does not request access to your contacts, location, camera, microphone, or general file storage. It reads and saves files only through the Android file picker, for the files you choose.

## Third-party code

SparkWriter includes open-source libraries (AndroidX and OkHttp, among others). None of them collects data or contacts a service on the developer's behalf.

## Deleting your data

The developer holds no data about you, so there is nothing to request from the developer.

Everything SparkWriter stores is on your device, and you control it:

- In **Settings**, you can clear the saved profile values.
- In Android **Settings → Apps → SparkWriter → Storage**, choose **Clear storage** to erase all app data.
- **Uninstalling** the app erases all of it.

Files you exported or saved through the file picker belong to you and live where you saved them. Delete them with your file manager.

## Children

SparkWriter is not directed at children and collects no personal information from anyone.

## Changes

If this policy changes, the updated version will be posted here with a new effective date.

## Contact

Jesse Weber — me@jesseweber.net

# SimNest Privacy Policy

Version 1.0 · Effective 【Effective date】 · Updated November 6, 2026

*This is a translation. If it differs from the Chinese version, the Chinese version prevails.*

SimNest is provided by 【Operator name】 ("we"). This policy covers the SimNest iPhone app, the SimNest Gateway Android app and the SimNest service. Questions: 【Contact email】.

## How SimNest works

You turn one of your own Android phones into a SIM gateway. It receives your calls and SMS and passes them on to your iPhone, and to the iPhones of family members you invite. Your iPhone can also place calls and send SMS through the gateway phone.

**Before they leave the sending device, SMS content, caller numbers, contacts, call logs and number names are encrypted with the receiving device's key, so only that device can read them. Our servers pass on the ciphertext and cannot read it.** Call audio goes over an encrypted peer-to-peer connection. When a direct connection is not possible, our relay forwards the encrypted audio. Audio is never recorded or stored.

## What we process

**To run the service:**
- Device identifiers: a random ID derived from each device's public key, plus the public key itself.
- Apple push tokens.
- Pairing and family-sharing records, signed by the owner's device.
- Each gateway's SIM slots and carrier names.
- Encrypted number names.
- Short-lived call and message routing state, held in memory for at most 5 minutes.
- Connection data (IP address, port, time) in server and relay logs, kept for no more than 30 days.

**Only on your devices; we cannot read it:**
- SMS content and the other party's numbers.
- Caller numbers.
- Your iPhone contacts. These are read on the iPhone only, and only if you allow it.
- The gateway phone's contacts and call log. They are sent encrypted, only to an iPhone that is allowed to see them.
- Call audio.
- Your in-app settings.

**Only when you choose to send them:**
- Diagnostics logs. These record app events and settings states. They contain no phone numbers, message text or contacts; long digit runs are masked before upload. We keep them for 30 days.
- Diagnostics screenshots. When you send a diagnostics log, you may choose up to three screenshots to send with it; none are sent unless you pick them. The app uses the system photo picker and receives only the images you choose; it does not read your photo library. Screenshots may show phone numbers, message text or contacts, so the app shows them again and asks you to check them before sending. They are scaled down and re-saved before upload, which removes location and other photo details. They are kept with their log for 30 days and used only to look into the problem you reported.

**What we never do:**
- We do not collect your name, phone number, location, photos or advertising identifiers.
- We do not track you across apps or websites, profile you or show ads.
- We do not use third-party analytics or advertising SDKs.
- We do not sell your information.

## Permissions

**iPhone:**
- Microphone, for calls.
- Notifications.
- Camera, only to scan the pairing code.
- Contacts (optional), read on the device only.

**Gateway (Android):**
- Phone, call log and phone numbers.
- SMS. The gateway becomes the default SMS app.
- Contacts, read only on request from an allowed iPhone.
- Notifications.
- Display over other apps.
- Start at boot.
- Write secure settings, granted to itself only to re-enable wireless debugging for its audio bridge.

## Family sharing and other people's data

Owners choose what each family member may do, and can change or revoke it at any time. When a family member is removed, or loses the view right, the messages they received from that gateway are deleted from their iPhone. Messages, calls and contacts include information about other people. You are responsible for having the right to process it.

## Where data is kept, and security

Notifications are delivered through Apple Push Notification service, which receives only the push token, the words "新短信" and ciphertext.

Connections use TLS with certificate pinning. End-to-end encryption uses HPKE (X25519 / HKDF-SHA256 / AES-128-GCM) with device signatures. Private keys stay in the iPhone Secure Enclave or Keychain, and in the Android Keystore.

## Retention and deletion

- Device, pairing and sharing records are kept until you unpair or ask us to delete them.
- Routing state is kept for at most 5 minutes.
- Server and relay logs are kept for up to 30 days.
- Diagnostics logs and the screenshots sent with them are kept for 30 days.

You can unpair at any time in the app, or email 【Contact email】 and we will delete all server records for your devices within 15 business days.

## Your rights

You may access, copy, correct or delete your information, or withdraw consent, by emailing 【Contact email】.

## Children

SimNest is intended for adults. Children under 14 may use it only with a guardian's consent.

## Changes

We will tell you in the app when this policy changes, and ask for your consent again for material changes.

## Contact

【Operator name】 · 【Address】 · 【Contact email】

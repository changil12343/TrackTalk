# Release signing

This document is the operator handoff for Google Play release signing. It does
not contain private keys, passwords, machine-specific paths, or Play Console
state.

## Current application identity

- Application ID and namespace: `com.trackvoice`.
- Current release version: `0.1.0` (`versionCode` 1).
- Release shrinking/minification is disabled. The default optimized ProGuard
  file and `app/proguard-rules.pro` are declared but R8 does not shrink the
  current release build.
- Debug builds use the standard Android debug signing configuration.
- A release build is unsigned unless complete upload-key credentials are
  supplied.

Do not change the version only to prepare signing. Before the first Play
upload, the operator must decide whether the product version remains `0.1.0`
or becomes `1.0.0`. The repository cannot establish whether `versionCode` 1
has already been uploaded to Play Console.

## Credential inputs

The preferred local input is an untracked `keystore.properties` file at the
repository root. Its four required entries are:

```properties
storeFile=<absolute-external-keystore-path>
storePassword=<operator-supplied-secret>
keyAlias=<confirmed-upload-alias>
keyPassword=<operator-supplied-secret>
```

Keep `storeFile` outside the repository. In a Windows Java properties file,
use forward slashes or escape each backslash. Never commit the populated
properties file.

CI can supply the same inputs without a file:

```text
TRACKTALK_UPLOAD_STORE_FILE
TRACKTALK_UPLOAD_STORE_PASSWORD
TRACKTALK_UPLOAD_KEY_ALIAS
TRACKTALK_UPLOAD_KEY_PASSWORD
```

The local properties value takes precedence over its corresponding environment
variable. A partial credential set fails during Gradle configuration without
printing any credential value. Complete credentials configure the `release`
signing config. With no credentials, ordinary release tasks remain unsigned so
existing development validation continues to work.

For a release that must be signed, use the explicit required-signing flag:

```powershell
.\gradlew.bat :app:bundleRelease '-Ptracktalk.requireReleaseSigning=true' --no-configuration-cache
```

That command fails clearly if the four credentials are absent, incomplete, or
refer to a missing keystore. Do not treat a successful unsigned
`:app:bundleRelease` run as a publishable release.

## Play App Signing key roles

- **Upload key:** the developer/operator keeps this private key and uses it to
  sign the AAB submitted to Play Console. Google verifies an upload using its
  registered public upload certificate. Play App Signing allows the upload key
  to be reset if it is lost or compromised.
- **App signing key:** Google Play keeps this key when Play App Signing is used
  and signs the APKs ultimately delivered to users. Its fingerprint, not the
  upload-key fingerprint, is normally registered with certificate-bound API
  providers for the Play-distributed app.

The two roles and fingerprints are intentionally distinct. See the official
[Android app-signing guide](https://developer.android.com/studio/publish/app-signing)
and [Play App Signing help](https://support.google.com/googleplay/android-developer/answer/9842756).

The current repository has no Google Sign-In, Firebase Auth, Maps, OAuth, App
Links, or other certificate-bound API configuration. Re-audit this if such an
integration is added; it will generally need the Google Play app-signing
certificate fingerprint rather than only the upload-key fingerprint.

## Upload key generation handoff

Do not run this step until the operator has confirmed the alias and secure
storage location. `tracktalk-upload` is the proposed alias, not a committed
identity decision.

Store the keystore outside the repository, for example under a restricted
operator-controlled key directory. For broad compatibility with Play, use an
RSA key of at least 2048 bits and a validity of at least 25 years. This command
uses RSA 2048 and 10,000 days and prompts interactively for passwords so they
are not placed in shell history:

```text
keytool -genkeypair -v -keystore "<external-keystore-path>" -alias "<confirmed-upload-alias>" -keyalg RSA -keysize 2048 -validity 10000
```

After creation, keep at least two independent encrypted backups of the
keystore. Keep password recovery material separately from the keystore backups.
Do not use GitHub, repository artifacts, or a shared plaintext folder as a
backup. Upload-key reset is available with Play App Signing, but it is not a
substitute for controlled storage.

## Public certificate and fingerprints

Export the public upload certificate to a clearly separate, non-private output
location. Omit password arguments so `keytool` prompts interactively:

```text
keytool -export -rfc -keystore "<external-keystore-path>" -alias "<confirmed-upload-alias>" -file "<certificate-output-path>"
```

The PEM certificate can be shared with Play Console and does not contain the
private key. Display its fingerprints without accessing the private key:

```text
keytool -printcert -file "<certificate-output-path>"
```

Record the upload certificate SHA-256 fingerprint in the controlled release
record. After Play App Signing is configured, separately record the app-signing
certificate SHA-256 fingerprint shown by Play Console. Label both fingerprints
by role.

## Signed AAB build and verification

With complete credentials, the required-signing build produces:

```text
app/build/outputs/bundle/release/app-release.aab
```

Verify the bundle's JAR signature and inspect its signer certificate:

```text
jarsigner -verify -strict -verbose -certs app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

Acceptance requires a positive `jar verified` result, a signer certificate,
and an AAB SHA-256 signer fingerprint equal to the exported upload certificate
fingerprint. `jar is unsigned` is a failure even if a tool returns a successful
process exit code.

Only after those checks should the operator configure or confirm Play App
Signing and upload the signed AAB to the intended Internal or Closed testing
track.

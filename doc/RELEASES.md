# Building releases on GitHub

The `Build` workflow can produce a signed release APK without the signing
key or the Maps key ever being in the repository. They are stored as
encrypted secrets in a GitHub environment named `release`, and the release
job cannot read them until you approve the run.

## One-time setup

### 1. Create the `release` environment

Repository **Settings → Environments → New environment**, name it `release`.

- Tick **Required reviewers** and add yourself. Every release run then
  waits for your approval before it can touch the secrets.
- Under **Deployment branches and tags**, choose **Selected branches and
  tags** and add the tag rule `v*`, plus the branch `main` if you want to
  be able to start a run by hand.

### 2. Add the secrets to that environment

In the environment page, under **Environment secrets**:

| Secret | Value |
|---|---|
| `SIGNING_P12_BASE64` | The `aprsdroid.p12` file, base64-encoded (see below) |
| `SIGNING_STORE_PASSWORD` | The password of the `.p12` |
| `MAPS_API_KEY` | The "APRSdroid Maps SDK (Android)" key from Google Cloud. Optional: without it the Google Maps SDK map type is hidden |

To base64-encode the file on Windows (PowerShell), which copies the result
straight to the clipboard without writing it to disk:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\aprsdroid.p12")) | Set-Clipboard
```

On macOS: `base64 -i aprsdroid.p12 | pbcopy`. On Linux: `base64 -w0 aprsdroid.p12`.

The key alias defaults to `aprsdroid`. If yours differs, add an environment
**variable** (not a secret) named `SIGNING_KEY_ALIAS`.

The Map Tiles key is not part of any build. It is typed into the app under
Settings → Map.

## Making a release

```
git tag v2.2.0
git push origin v2.2.0
```

1. The `test` job runs the unit tests, lint and a debug build. It has no
   access to secrets.
2. The `release` job waits for your approval (GitHub emails you, or open
   the run under the **Actions** tab and choose **Review deployments**).
3. After approval it builds and signs the APK, checks that the signer is
   the expected certificate, and publishes a GitHub Release with the APK
   attached. The app's "Check for updates" looks at these releases.

You can also start it by hand: **Actions → Build → Run workflow**. A manual
run attaches the APK to the workflow run instead of publishing a release.

## What this protects, and what it does not

- Secrets are encrypted at rest, masked in logs, and never given to pull
  requests or to the `test` job.
- Anyone who can push to the repository can change the workflow, but a
  changed workflow still cannot read the secrets until you approve the run.
  Look at what you are approving if the workflow file changed.
- The signing key does leave your computer: GitHub stores it. If the GitHub
  account were compromised, revoke the certificate and issue a new one.
- The Maps SDK key is embedded in every APK by design. Its protection is
  the restriction in Google Cloud to this package name and signing
  certificate.
- The workflow refuses to publish an APK signed with any key other than the
  certificate whose SHA-1 is in `EXPECTED_SIGNER_SHA1`. Update that value in
  `.github/workflows/build.yml` if the certificate is ever replaced.

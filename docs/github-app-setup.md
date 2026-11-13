# GitHub App setup

FirstPass uses two GitHub Apps:

- a **development app**, whose webhooks are relayed to your machine by [smee.io](https://smee.io);
- a **production app**, whose webhooks go to the AWS Lambda function URL. It is only needed to deploy FirstPass to your own AWS account ([docs/deploy.md](deploy.md)); this project runs locally instead ([ADR 0016](adr/0016-deployment-ready-without-a-cloud-account.md)).

This guide covers the development app. Never commit the private key, the secrets or the `.env` file; `.gitignore` already excludes `.env` and `*.pem`.

## 1. Create a smee channel

Open <https://smee.io/new> and copy the channel URL. Anyone who knows this URL can read the payloads relayed through it, so keep it private and install the app only on a test repository.

## 2. Generate a webhook secret

A random 32-byte value, in hexadecimal. In Git Bash:

```bash
openssl rand -hex 32
```

Or in Windows PowerShell:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
-join ($bytes | ForEach-Object { $_.ToString('x2') })
```

## 3. Register the app

On GitHub: **Settings → Developer settings → GitHub Apps → New GitHub App** ([GitHub docs](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app)).

| Field | Value |
| --- | --- |
| GitHub App name | Unique across GitHub, 34 characters at most, for example `FirstPass Dev <your-username>` |
| Homepage URL | The repository URL |
| Callback URL | `http://localhost:8080/api/v1/auth/callback` (used by the dashboard sign-in) |
| Expire user authorization tokens | Keep selected (GitHub recommends it) |
| Request user authorization (OAuth) during installation | Not selected |
| Enable Device Flow | Not selected |
| Webhook: Active | Selected |
| Webhook URL | Your smee channel URL |
| Webhook secret | The value from step 2 |
| SSL verification | Enabled |
| Where can this GitHub App be installed? | Only on this account |

**Repository permissions.** Grant only these; leave everything else on *No access*:

| Permission | Access | Used for |
| --- | --- | --- |
| Pull requests | Read and write | Read pull requests and their files, post reviews and comments |
| Contents | Read-only | Read `.firstpass.yml` from the base branch, compare commits |
| Checks | Read and write | Create and complete the FirstPass check run |
| Metadata | Read-only | Mandatory; repository information |

**Subscribe to events:** Pull request.

Later features need more, and the app settings can be updated then:

- `/firstpass` commands use the **Issue comment** event, which requires at least read access to the **Issues** permission ([webhook events](https://docs.github.com/en/webhooks/webhook-events-and-payloads#issue_comment)). Adding an emoji reaction to a comment requires write access to **Issues**, while a reply comment on a pull request only needs **Pull requests** write ([permissions for GitHub Apps](https://docs.github.com/en/rest/authentication/permissions-required-for-github-apps)).
- The dashboard sign-in needs a **client secret**, generated in the app settings.

## 4. Generate the private key

In the app settings, under **Private keys**, select **Generate a private key**. GitHub downloads a PEM file in PKCS#1 format ([GitHub docs](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/managing-private-keys-for-github-apps)).

1. Move the file **outside the repository folder**.
2. The backend reads keys in PKCS#8, the format that Java's `KeyFactory` supports natively. Convert the key once:

   ```bash
   openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in firstpass-dev.pem -out firstpass-dev.pkcs8.pem
   ```

3. Private keys do not expire. If one leaks, delete it in the app settings and generate a new one.

## 5. Fill in `.env`

Copy `.env.example` to `.env` at the repository root, then set:

- `GITHUB_APP_ID` and `GITHUB_CLIENT_ID`, shown on the app's **General** page;
- `GITHUB_APP_PRIVATE_KEY_PATH`, the absolute path of the PKCS#8 key;
- `GITHUB_WEBHOOK_SECRET`, the value from step 2;
- `SMEE_URL`, the channel URL from step 1.

## 6. Install the app on a sandbox repository

Create a public test repository, for example `firstpass-sandbox`. On the app page, select **Install App**, choose your account, then **Only select repositories** and pick the sandbox.

## 7. Run FirstPass locally and relay webhooks to it

For a real review, set `FIRSTPASS_LLM_PROVIDER=gemini` and `GEMINI_API_KEY` in `.env`. With `fake`, the review only says that nothing was found.

In a first terminal, start DynamoDB Local and the local server:

```bash
docker compose up -d
cd backend
./gradlew :local-dev:run
```

On start, the server creates the table in DynamoDB Local, which keeps nothing between restarts, and records the app's installations and repositories from GitHub. It then listens on `http://127.0.0.1:8080/webhook` and logs each delivery and each job, without any code or secret.

In a second terminal, relay the webhooks of your smee channel to it:

```bash
npx smee-client@5.0.0 --url https://smee.io/<your-channel> --target http://localhost:8080/webhook
```

Open a pull request on the sandbox repository. The app settings list recent deliveries under **Advanced**, where you can also redeliver one from the last three days.

**Signatures through smee.** smee.io and smee-client read the JSON payload and encode it again before they forward it. The local server still checks GitHub's signature over the bytes it receives, so a delivery whose JSON changes on the way is refused with 401, and the server log says so. Escaped characters and non-ASCII text are the usual cause, so keep the titles and descriptions of test pull requests to plain text. If deliveries keep failing, use a tunnel that forwards the original bytes instead of smee, such as a Cloudflare quick tunnel (`cloudflared tunnel --url http://localhost:8080`), and set the app's webhook URL to the tunnel address followed by `/webhook`. That address changes each time the tunnel starts.

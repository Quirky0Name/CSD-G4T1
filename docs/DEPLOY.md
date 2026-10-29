# Deploying to AWS

The whole app runs on one EC2 instance, as the root `docker-compose.yml`, with CloudFront in front
for HTTPS on a `*.cloudfront.net` address. Everything is in one CloudFormation template,
`deploy/stack.yml`: deleting the stack removes it all. There's no SSH; Session Manager opens a
shell in the browser when one is needed.

```
browser --HTTPS--> CloudFront --HTTP, port 80--> EC2: Caddy -> storage, users, updating, research evaluation, adminer
                                                       Postgres, GROBID, the PDFs on the instance's disk
```

Only CloudFront can reach the instance. The disk is snapshotted every night and the last 7 are
kept. Why CloudFront and not a DuckDNS name: SMU's network blocks dynamic DNS sites, which would
take the app off campus Wi-Fi (DECISIONS.md, 2026-10-28).

## Launching it (browser only, once)

Use a personal AWS account (or one with credits), in N. Virginia (us-east-1), the cheapest
region; every click pays about a quarter of a second more than from Singapore. AWS Academy's
Learner Lab won't do: it limits IAM roles and stops instances after a few hours.

1. **Secrets.** In Systems Manager, Parameter Store, create a parameter per setting under
   `/research-assistant/`, each a SecureString, named exactly as in `.env.example`. At least:

   | Parameter | Value |
   |---|---|
   | `/research-assistant/JWT_SECRET` | `openssl rand -base64 32` |
   | `/research-assistant/POSTGRES_PASSWORD` | letters and digits only |
   | `/research-assistant/ADMIN_PASSWORD_HASH` | `docker run --rm caddy:2.11-alpine caddy hash-password` (for `/adminer`) |

   Then any of the API keys and settings in `.env.example` the app should use (`GEMINI_API_KEY`,
   `OPENALEX_API_KEY`, `CROSSREF_MAILTO`, `TELEGRAM_BOT_TOKEN`, `NOTIFY_TELEGRAM_CHAT_ID`,
   `DEV_TOOLS`, ...). Leave out `WEB_PORT`, `HTTPS_PORT` and `SITE_ADDRESS`: the server sets those.
   No value may contain a single quote.
2. **CloudFront's prefix list.** In the VPC console, Managed prefix lists, find
   `com.amazonaws.global.cloudfront.origin-facing` and copy its ID (`pl-...`).
3. **The stack.** In CloudFormation, Create stack, upload `deploy/stack.yml`, call it
   `research-assistant`, paste the prefix list ID, keep the other defaults, tick the IAM
   acknowledgement, and create it. It takes about 10 minutes, then the instance needs about 15
   more to build the images and start everything.
4. **Open it.** The stack's Outputs tab has `SiteUrl`. Register an account. `AdminerUrl` asks for
   `admin` and the password you hashed, then Postgres's login (server `db`, user `app`,
   `POSTGRES_PASSWORD`, database `research_assistant`).

The first boot's log is `/var/log/research-assistant-bootstrap.log` on the instance.

## Redeploying on every merge

`.github/workflows/deploy.yml` runs on every merge to `main`:

1. **Tests:** Storage Management and User Management (Maven), Updating and Research Evaluation
   (pytest) and the frontend build. Anything failing stops the deploy.
2. **Images:** the five built services, on GitHub's ARM runners (the server is ARM), pushed to
   GitHub's registry as `ghcr.io/<owner>/<repo>/<service>`, tagged with the commit's SHA (and
   `main`).
3. **Deploy:** GitHub signs in to AWS with a short-lived token (no stored keys) as the stack's
   deploy role, which can do one thing: run `deploy/update.sh <commit>` on this instance through
   Systems Manager. The server pulls that commit's images and restarts; nothing is built there.
   The workflow waits and fails if the server's update does.

Runs never overlap: one waits for the other to finish, and GitHub keeps only the newest waiting
run, so a rollback queued behind a deploy can be replaced by a merge that lands meanwhile. Each
commit on `main` shows a tick or a cross for its deploy.

**Setting it up (once):**

1. Update the stack: CloudFormation, the `research-assistant` stack, Update, Replace existing
   template, upload this `deploy/stack.yml`, keep every parameter. It adds GitHub as an identity
   provider and the deploy role; the instance isn't touched. If IAM, Identity providers already
   lists `token.actions.githubusercontent.com`, set `CreateGitHubOidcProvider` to `false`.
2. A repo admin adds the variable: GitHub, the repo, Settings, Secrets and variables, Actions,
   Variables, New repository variable: `AWS_DEPLOY_ROLE_ARN` = the stack's `DeployRoleArn` output.
   Until it's set, the workflow tests and builds but doesn't deploy.
3. After the first run, the packages' owner (the repo's owner, Quirky0Name, since they're under
   that account) makes the five packages public: the owner's profile, Packages, each package,
   Package settings, Change visibility, Public. Then the server can pull them without logging in.
   They hold only the repo's code; secrets come from Parameter Store when the containers start.
   Until then the server builds the images itself, slowly and tight on memory, and the run shows
   a warning saying so.
4. Once, right after this setup reaches `main`, run the new update by hand, since the server
   still has the old `update.sh` (Session Manager, then
   `sudo -i`, `cd /opt/research-assistant`, `git pull`, `deploy/update.sh`). Deploys from GitHub
   use the new one from then on.

**Rolling back:** GitHub, Actions, Deploy, Run workflow, and give an earlier commit on `main`
(its SHA, short is fine). It tests, builds and deploys that commit; anything not on `main` is
refused.

**Pausing it** (a demo week, say): Actions, Deploy, the `...` menu, Disable workflow.

## Day to day

Open a shell: Systems Manager, Session Manager, Start session, pick the `research-assistant`
instance. Then `sudo -i` and `cd /opt/research-assistant`.

- **Deploy by hand:** `deploy/update.sh` (the latest `main`) or `deploy/update.sh <commit>`; it
  uses that commit's images if the workflow built them, and builds them on the server if not.
- **Change a setting:** edit it in Parameter Store, then redeploy; every deploy rewrites `.env`
  from Parameter Store first.
- **Logs:** `docker compose logs -f storage` (or `users`, `updating`, `research-evaluation`, `web`).
- **Status:** `docker compose ps`.
- **The demo tools:** on unless `/research-assistant/DEV_TOOLS` is `false` (ROADMAP.md says when
  to turn them off).

A reboot brings everything back on its own: `research-assistant.service` starts the stack, waits
for it to be healthy, then restarts Updating so its startup poll runs with Storage Management up.

## Pausing and deleting

- **Pause:** stop the instance in the EC2 console. Compute stops billing; the disk, the snapshots
  and the fixed IP (so CloudFront's link) stay. Start it again and the stack comes back, then
  catches up by itself: if `main` moved on while it was stopped, it deploys the latest `main`
  (`deploy/catch-up.sh`), but only from the workflow's images, so only a commit that passed the
  tests. A merge while it's stopped ends the deploy job with a warning, not a failure.
- **Delete:** delete the stack. The instance, its disk, the IP, CloudFront and the roles go.
  The nightly snapshots stay until deleted by hand (EC2, Snapshots), and so do the parameters.

## Restoring from a snapshot

1. Stop the instance.
2. EC2, Snapshots: pick one, Create volume from snapshot, in the instance's availability zone,
   with the tag `Backup` = `research-assistant`, or the nightly snapshots stop.
3. Volumes: detach the instance's current root volume, attach the new one as `/dev/sda1`.
4. Start the instance. The database and the PDFs are back as they were that night.
5. Delete the old volume once you're happy, since it keeps billing. A volume attached by hand
   also survives deleting the stack, so delete it by hand then too.

## Cost (approximate list prices, us-east-1; check before launching)

| Item | Per month |
|---|---|
| t4g.medium (2 vCPU, 4 GB, ARM), always on | ~US$25 |
| 30 GB disk | ~US$2.40 |
| Public IP | ~US$3.60 |
| Snapshots (7 kept) | ~US$1–2 |
| CloudFront | free tier (1 TB, 10 million requests) |
| **Always on** | **~US$32** |
| **Stopped** (disk, IP, snapshots) | **~US$7** |

It's cheapest to keep the instance stopped except in the weeks it's needed: about US$0.85 a day
running. New accounts' sign-up credits (Billing, Credits) may cover all of it.

## Limits worth knowing

- CloudFront waits at most 60 seconds for an answer. A "poll now" over many papers can take
  longer; the poll still finishes on the server, but the browser shows an error.
- The last hop, CloudFront to the instance, is plain HTTP over AWS's network; the browser's side
  is HTTPS.
- `/adminer` is the database's superuser behind two passwords; keep both strong.

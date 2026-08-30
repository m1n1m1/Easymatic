---
title: Mail
description: Adding an account, the app-password trap, and the four mail nodes.
sidebar:
  order: 5
---

Easymatic talks IMAP and SMTP directly. There is no OAuth and no vendor SDK, which is
why any mail server works — and why **you almost certainly need an app password rather
than your normal one**.

## The app-password trap

Google, Yahoo and Apple have all withdrawn plain-password access to IMAP and SMTP. Your
normal account password will be rejected with an authentication error that explains
nothing.

The editor's provider presets each carry the sentence for their own service and a link
to the page where the app password is made:

| Provider | Where the app password comes from | Note |
| --- | --- | --- |
| **Gmail** | `myaccount.google.com/apppasswords` | Turn 2-Step Verification on first |
| **Yahoo Mail** | Account Security | |
| **iCloud Mail** | `appleid.apple.com` | Sign in with your full `@icloud.com` address even if you normally use `@me.com` |
| **Fastmail** | Settings → Security → App passwords | Give it Mail (IMAP/SMTP) access |
| **GMX** | — | Switch IMAP on in GMX's web settings first; it is off by default |

### Outlook.com and Microsoft 365 cannot be added

Microsoft has switched password sign-in off for IMAP and SMTP on personal and
Microsoft 365 accounts alike, leaving OAuth as the only way in — which is a different
feature, not a different hostname.

That row is present in the picker **as a row that refuses**, deliberately. Leaving it
out would leave you typing `smtp.office365.com` by hand and reading an
`AUTHENTICATE failed` that names nothing about why.

## Adding an account

1. **Setup → Mail accounts → +**.
2. Type the address. The preset is chosen from its domain automatically — including for
   an Outlook address, so it can be refused rather than handing you an empty form.
3. Paste the app password.
4. For **Other**, fill in the servers yourself. The defaults are the standard ones:
   SMTP on 587 with STARTTLS, IMAP on 993 with TLS.
5. Name the account — "Work", "Alarm system". That name is what pickers show.

**Username** is only needed when it differs from the address, which no preset does.

STARTTLS and TLS are not two flavours of one thing: TLS wraps the socket before a byte
of the protocol is spoken (the historic ports 465 and 993), where STARTTLS opens in the
clear on the ordinary port and upgrades on command (587 and 143). Servers disagree
about which they offer, which is why it is set per protocol.

**None** exists because a self-hosted server on a LAN is a real configuration, not
because it is ever a good idea over the internet.

Your password is **sealed on the device** and never read back into the UI. Two accounts
on one address — a personal and a work alias on the same server — is a real setup, so
accounts are identified internally rather than by address, and renaming or re-addressing
one never breaks the nodes pointing at it.

## The nodes

| Node | What it does |
| --- | --- |
| **Send Email** | Sends through one of your accounts |
| **Email Received** (trigger) | Fires when mail arrives in a mailbox you are watching |
| **Fetch Email** | Reads messages onto a list you can loop over |
| **Update Email** | Marks read or unread, moves to another folder, or deletes |

*Fetch* and *Update* are the read-and-act pair: fetch onto a list, loop over it, act on
each one through the reference it hands you.

The folder field on those nodes lists the folders of **whichever account you chose**, so
picking the account narrows what the folder field offers.

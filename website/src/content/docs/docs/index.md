---
title: Documentation
description: How Ottomatic's node graph works, and a reference for every node in it.
sidebar:
  order: 0
---

Ottomatic automates a phone by wiring nodes together. A **trigger** says when something
happens, an **action** does something about it, and **values** and **transforms** feed
them the data they need.

## Node reference

Every node ships with a page listing its ports, its configuration and anything it needs
from the phone. Those pages are generated from the node declarations themselves, so they
cannot describe a port a node no longer has.

Start with the group you want in the sidebar, or search — the search box covers every
node name and description.

## Guides

The plugin guide and the external API guide are the two externally-facing ones. They
still live in the repository, at `docs/PLUGINS.md` and `docs/EXTERNAL_API.md`, and have
not been brought across yet.

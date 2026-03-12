# Security Policy

## Scope

Livewire is a **developer tool**. It is explicitly designed for local development
and sandboxed environments only. It is not intended to be deployed in production
or exposed to untrusted networks — ever.

If you are using Livewire in production, that is a misconfiguration, not a
vulnerability report.

## What Livewire does (and why it matters)

Livewire embeds a Clojure nREPL server inside a running Spring Boot application.
The nREPL is a **full code execution environment** — anyone who can connect to
the port can execute arbitrary JVM code, read and write to the database, call
external services, and access anything the JVM process can access.

By default, Livewire binds to `127.0.0.1` only. Do not change this to `0.0.0.0`
unless you understand exactly what you are exposing and why.

When an AI agent drives Livewire, **query results, entity field values, and bean
state are sent to the model provider**. Always use anonymized or synthetic seed
data — never a database containing real user data, PII, or credentials.

## Reporting a vulnerability

If you discover a security issue in Livewire itself (e.g. the nREPL binds to a
broader interface than expected, autoconfiguration activates in an unintended
environment, or sensitive data is logged unintentionally), please report it
privately rather than opening a public issue.

Contact: **tomas.brejla@gmail.com**

Please include:
- A description of the issue and its potential impact
- Steps to reproduce
- Affected version(s)

I aim to respond within 5 business days.

Bloated Shelf — App-Specific Notes for Livewire

This file captures Bloated-Shelf-specific conventions and runtime facts that are useful
when driving the app from the Livewire REPL.

---

## Authentication

When using `run-as` against the live Bloated Shelf app, use the system superadmin account:

```clojure
(lw/run-as "superadmin@example.com"
  ...)
```

This account is granted `ROLE_USER` + `ROLE_ADMIN` automatically by the
`->authentication` helper (string-form dispatch), which is sufficient for most
`@PreAuthorize`-guarded service calls.

---

## Connection

- nREPL port: **7888**
- Project directory: `/home/brdloush/projects/bloated-shelf`

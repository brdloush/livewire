# Internal Testing and Tracing Examples

This file contains useful Clojure s-expressions (snippets) that we use during interactive REPL-driven development to test and trace the live application.

## SQL Tracing and N+1 Detection

### The `bookController` N+1 Issue
This snippet was used to trace a service call that uncovered a severe N+1 query problem (30 queries, ~1000ms duration) where the service fetches `client_account_yields` individually for each account in a loop.

```clojure
(require '[net.brdloush.livewire.core :as lw]
         '[net.brdloush.livewire.trace :as trace])

(trace/trace-sql
  (.getAllBooks (lw/bean "bookController") 
                      25))
```

*Note: Use this snippet to verify performance fixes (e.g., adding `JOIN FETCH` or `@EntityGraph` to the repository method) once the underlying code is modified.*

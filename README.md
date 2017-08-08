# otplike

[![Build Status][1]][2]

_otplike_ is a framework built on top of [_core.async_][3]. It emulates basic
[_Erlang/OTP_][4] concepts, such as processes, process linking, monitoring,
standard behaviours.

# Rationale

Although _core.async_ provides a solid foundation for asynchronous
applications, our experience shows that there is a need in higher level
system building blocks.

It appears that certain ideas can be taken from Erlang/OTP and implemented
on top of _core.async_.

The `gen_server` equivalent is used to serialize sync/async access to state
and ensure that possibly inconsistent state data will be discarded in case
of a crash.

Process linking/monitoring improves crash/error propagation and supervision
covers recovery. In addition process tracing facility helps a lot with
application debugging and profiling.

It is obvious that due to JVM limitations otplike cannot replace Erlang/OTP
and otplike will `NEVER` be seen as Erlang/OTP alternative.

# Example

## Echo Server
```clojure
(require '[otplike.process :as process :refer [!]])

(process/proc-defn server []
  (println "server: waiting for messages...")
  ; wait for messages
  (process/receive!
    [from msg]
    (do
      (println "server: got" msg)
      ; send response
      (! from [(process/self) msg])
      (recur))
    :stop
    ; exit receive loop
    (println "server: stopped")))

(process/proc-defn client []
  ; spawn process
  (let [pid (process/spawn server)]
    ; send message to it
    (! pid [(process/self) :hello])

    ;wait for response
    (process/receive!
      [pid msg]
      (println "client: got" msg))

    ; ask spawned process to stop
    (! pid :stop)))

(process/spawn client)
```

More examples are available under the /examples directory.

# Releases and Dependency Information

[![Clojars Project][5]][6]

[All Released Versions][7]

_Leiningen_ dependency information:

    [otplike "0.2.0-alpha"]

# Documentation

* [API docs][8]
* [Examples][9]

# Known issues

* As long as java uses "real" threads and some internals still block
(e.g. `gen-server/call`, termination of `gen-server` and `trace/send-trace`)
you can run out of threads and hang forever using a lot of such operations
simultaneously
* A chain of N processes, when each next process is created by the previous
one, holds amount of memory proportional to N until the **last** process' exit
* Timers can fire with significant delay (up to 20 ms) for the first time
after appilcation start

# Plans

* Return timeouts from `gen-server` callbacks
* Replace/complement all internal blocking with parking
* ClojureScript compatibility
* `application` behaviour and related features as configuration
* "Simple" supervisor (analogous to `simple_one_for_one` in Erlang) as
a separate module
* Tracing and introspection
* More advanced examples/tutorial

# Contributing

Please use the project's GitHub issues page for all questions, ideas,
etc. Pull requests are welcome. See the project's GitHub contributors
page for a list of contributors.

## License

Copyright © 2017 [SUPREMATIC][10] and contributors.

Distributed under the Eclipse Public License v1.0,
the same as Clojure. License file is available under the project root.

## Changelog

* Release 0.2.0-alpha on 17.05.2017 [NOTES][11]
* Release 0.1.0-SNAPSHOT on 15.08.2016

[1]: https://travis-ci.org/suprematic/otplike.svg?branch=master
[2]: https://travis-ci.org/suprematic/otplike
[3]: https://github.com/clojure/core.async
[4]: http://www.erlang.org/
[5]: https://img.shields.io/clojars/v/otplike.svg
[6]: https://clojars.org/otplike
[7]: https://clojars.org/otplike
[8]: https://suprematic.github.io/otplike/api/index.html
[9]: https://github.com/suprematic/otplike/tree/master/examples/otplike/example
[10]: http://suprematic.net/
[11]: https://github.com/suprematic/otplike/releases/tag/0.2.0

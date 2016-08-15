# otplike

_otplike_ is a framework built on top of [_core.async_](https://github.com/clojure/core.async). It
emulates basic [_Erlang/OTP_](http://www.erlang.org/) concepts, such as processes, process 
linking, monitoring, standard behaviours.

# Rationale

Although _core.async_ provides a solid foundation for 
asynchronous applications, our experience shows that 
there is a need in higher level system building blocks.

It appears that certain ideas can be taken from Erlang/OTP and
implemented on top of _core.async_. 

The `gen_server` equivalent is used to serialize sync/async 
access to state and ensure that possibly inconsistent state 
data will be discarded in case of a crash. 

Process linking/monitoring improves crash/error propagation
and supervision covers recovery. In addition process 
tracing facility helps a lot with application debugging and 
profiling. 

It is obvious that due to JVM limitations otplike cannot replace 
Erlang/OTP and otplike will `NEVER` be seen as Erlang/OTP 
alternative.

# Example

## Echo Server
```clojure
(:require [otplike.process :as process :refer [!]])

(process/defproc server [inbox]
  (println "server: waiting for messages...")
  ; wait for messages
  (loop []
    (process/receive!
      [from msg] 
      (do
        (println "server: got" msg)
        ; send back response
        (! from [(process/self) msg])
        (recur))
      :stop 
      ; do nothing, and exit message loop
      (println "server: stopped"))))

(process/defproc client [inbox]
  ; spawn process
  (let [pid (process/spawn server [] {})]
    ; send message to it
    (! pid [(process/self) :hello])
    
    ;wait for response
    (process/receive!
      [pid msg] 
      (println "client: got" msg))
    
    ; ask spawned process to stop  
    (! pid :stop)))
    
(process/spawn client [] {})    
    
```

More examples are available under the /examples directory.

# Releases and Dependency Information

[![Clojars Project](https://img.shields.io/clojars/v/otplike.svg)](https://clojars.org/otplike)

[All Released Versions](https://clojars.org/otplike)

_Leiningen_ dependency information:

    [otplike "0.1.0-SNAPSHOT"]

# Documentation

* [API docs](https://suprematic.github.io/otplike/index.html)
* [Examples](https://github.com/suprematic/otplike/tree/master/examples/otplike/example)

# Known issues

* A chain of N processes, when each next process is 
created by the previous one, holds amount of memory 
proportional to N _until last process exits_.
* supervisors are not yet implemented

# Plans
* supersvisor behaviour
* ClojureScript compatibility
* More advanced examples/tutorial

# Contributing

Please use the project's GitHub issues page for all 
questions, ideas, etc. Pull requests welcome. See the 
project's GitHub contributors page for a list of
contributors.

## License

Copyright © 2016 Suprematic UG and contributors.

Distributed under the Eclipse Public License v1.0, 
the same as Clojure. License file is available under the project root.

## Changelog

* Release 0.1.0-SNAPSHOT on 15.08.2016

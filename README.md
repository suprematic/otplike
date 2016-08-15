# otplike

_otplike_ is a framework built on top of _core.async_. It
emulates basic Erlang/OTP concepts, such as processes, process 
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

Latest release: 0.1.0-alpha

* [All Released Versions][1]

[Leiningen][2] dependency information:

    [org.clojure/clojure "1.7.0"]
    [net.suprematic/otplike "0.1.0-alpha"]

[Maven][3] dependency information:

    <dependency>
      <groupId>net.suprematic</groupId>
      <artifactId>otplike</artifactId>
      <version>0.1.0-alpha</version>
    </dependency>


# Documentation

[API docs][4]

Look for examples in `examples` directory of a project.

# Known issues

* A chain of N processes, when each next process is 
created by the previous one, holds amount of memory 
proportional to N _until last process exits_.
* supervisors are not yet implemented

# Contributing

Please use the project's GitHub issues page for all 
questions, ideas, etc. Pull requests welcome. See the 
project's GitHub contributors page for a list of
contributors.


## License

Copyright © 2016 Suprematic UG.

Distributed under the Eclipse Public License v1.0, 
the same as Clojure.


## Changelog

* Release 0.1.0-alpha on 15.08.2016

[1]: http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.suprematic%22%20AND%20a%3A%22otplike%22
[2]: https://github.com/technomancy/leiningen
[3]: http://maven.apache.org/
[4]: https://suprematic.github.io/otplike/index.html

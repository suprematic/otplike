# otplike

otplike is a library on top of core.async, designed to create asynchronous
programs in Clojure. It implements Erlang/OTP concepts, such as process linking,
monitoring, standard behaviors and supervision.


## Releases and Dependency Information

Latest release: 0.2.0-alpha

* [All Released Versions][1]

[Leiningen][2] dependency information:

    [org.clojure/clojure "1.7.0"]
    [com.suprematic/otplike "0.2.0-alpha"]

[Maven][3] dependency information:

    <dependency>
      <groupId>com.suprematic</groupId>
      <artifactId>otplike</artifactId>
      <version>0.2.0-alpha</version>
    </dependency>


## Documentation

### Rationale

Although core.async provides a solid low-level foundation for asynchronous
applications, the practical experience shows that there is a need for higher
level features which may be required to create reliable systems. We believe
that certain aspects taken from Erlang/OTP can be implemented on top of
core.async. The `gen_server` equivalent is used to serialize sync/async access
to state and ensure that possibly inconsistent state data will be discarded
in case of a crash. Process linking/monitoring improves crash/error propagation
and supervision covers system recovery. In addition process/message tracing
facility helps a lot with application debugging and profiling. It is obvious
that due to JVM limitations otplike cannot replace Erlang/OTP, and certain
compromises must be made to get it working.

[API docs][4]

Look for examples in `examples` directory of a project.


## Known issues

* A chain of N processes, when each next process is created by the previous one,
holds amount of memory proportional to N _until last process exits_.


## Contributing

Please use the project's GitHub issues page for all questions, ideas, etc.
Pull requests welcome. See the project's GitHub contributors page for a list of
contributors.


## License

Copyright © 2016 Suprematic GmbH.

Distributed under the Eclipse Public License, the same as Clojure.


## Changelog

* Release 0.2.0-alpha on 2016.08.20


[1]: http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.suprematic%22%20AND%20a%3A%22otplike%22
[2]: https://github.com/technomancy/leiningen
[3]: http://maven.apache.org/
[4]: http://suprematic.github.io/otplike/

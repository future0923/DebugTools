# Traditional Spring MVC Tomcat Demo

This module is a traditional Spring MVC WAR application for Apache Tomcat 8.5.
It uses `web.xml`, Spring XML configuration, `javax.servlet`, and Servlet 3.1.

## IntelliJ IDEA configuration

1. Create a local Tomcat Server configuration using Tomcat 8.5.
2. Deploy `debug-tools-test-tomcat-legacy:war exploded`.
3. Set the application context to `/app`.
4. Start Tomcat and open `http://localhost:8484/app/b`.

The endpoint returns `b` when the application is deployed correctly.

# What is the proxy

- A proxy is a server that sits between a client and a server. Therefore, the proxy server acts on behalf of the client when requesting a resource, and it can hide the real origin of the request
- When server A communicates with server B through a proxy, A does not know who actually sent the request, nor does it know who the information will ultimately be sent to


## Forward proxy

- A forward proxy is a proxy server that can be accessed by any user.
- There are two common types of Forward proxies

### Anonymous proxy
-

### Transparent proxy
-

## Reverse proxy

- A reverse proxy is a proxy server that appears to clients just like a regular web server. It forwards client requests to one or more backend servers, then returns the response to the client as if it came directly from the reverse proxy itself. It is usually set up inside the private network of one or more backend servers, and all incoming traffic must pass through it

- In most cases, systems use a reverse proxy to protect applications that have weak HTTP handling capabilities. For example:

    - Handling high volumes of concurrent requests.

    - Supporting different protocol versions (such as HTTP/1.x, HTTP/2, etc.).

    - SSL/TLS termination (converting HTTPS to HTTP).

    - Caching requests.

    - Handling cookie and session data.

    - Splitting a single request into multiple smaller requests and aggregating the responses.


## Difference between Forward-Proxy và Reverse-Proxy

[client] -> [Forward-proxy] -> [Internet] -> [Server]
[client] -> [Internet] -> [Reverse-proxy] -> [Server] 


## Upstreams
### HTTP/SOCKS Upstream
|    name |  value | description |
|---------|--------|-------------|
|    type |  HTTP,SOCKS  |             |
| address | x.x.x.x |             |
|    port | ? | |
|    auth | | |
| username | | |
| password | | |


### Virtual Upstream Group
#### LB Group
|    name |  value | description |
|---------|--------|-------------|
|    type |  LB  |             |
|    algo |  | |
|    health-check | | |
| servers | | |
| server-1 | weight | |
| server-2 | weight | |

#### Chain Group
|    name |  value | description |
|---------|--------|-------------|
|    type |  chain  |             |
| servers | | |
| server-1 | | |
| server-2 | | |

#### Forwarding Rule Group
|    name |  value | description |
|---------|--------|-------------|
|    type |  rule |             |
| servers | | |
| server-1 | | |
| server-2 | | |

## Acceptors
|    name |  value | description |
|---------|--------|-------------|
| protocol |  HTTP,SOCKS |  |
| hostname | to bound | optional |
| port | to bound | required |
| server | | |


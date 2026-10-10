# e2e-26 #34: the strace sidecar of r26/store_force.py. It joins the Jenkins container's PID namespace
# (docker run --pid=container:<jenkins> --cap-add SYS_PTRACE) for the length of one section and is removed after it;
# the Jenkins container itself gets no extra capability, so no other unit is affected.
FROM alpine:3.24
RUN apk add --no-cache strace

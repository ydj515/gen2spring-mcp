FROM dxflrs/garage:v2.4.1@sha256:9c96caa2612d3411acc5b0e6701fb238dbfba33e533a6d7d3d811a4b12d0d020 AS garage

FROM alpine:3.22.1@sha256:4bcff63911fcb4448bd4fdacec207030997caf25e9bea4045fa6c8c44de311d1
COPY --from=garage /garage /usr/local/bin/garage
COPY init.sh /usr/local/bin/garage-init
ENTRYPOINT ["/bin/sh", "/usr/local/bin/garage-init"]

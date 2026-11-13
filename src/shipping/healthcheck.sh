#!/usr/bin/env bash
# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

# Equivalent of the original Rust healthcheck binary: exit 0 when a TCP connection to
# 127.0.0.1:$SHIPPING_PORT (default 50050) can be opened, 1 otherwise.
exec 3<>"/dev/tcp/127.0.0.1/${SHIPPING_PORT:-50050}" || exit 1
exec 3>&-

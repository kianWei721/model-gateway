package com.modelgateway.catalog;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

public record Provider(
        String name,
        Protocol protocol,
        URI baseUrl,
        Region region,
        Optional<InetSocketAddress> proxy,
        Duration connectTimeout,
        String bootstrapKeyEnv) {}

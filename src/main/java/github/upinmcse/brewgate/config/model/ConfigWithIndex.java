package github.upinmcse.brewgate.config.model;

import github.upinmcse.brewgate.config.GatewayConfig;

import java.math.BigInteger;

public record ConfigWithIndex(GatewayConfig config, BigInteger index) {
}

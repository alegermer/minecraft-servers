package com.myorg;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Environment;
import software.amazon.awscdk.core.StackProps;

public class MinecraftServersApp {
    public static void main(final String[] args) {
        App app = new App();

        new MinecraftServersStack(app, "mc-fabric", StackProps.builder()
                .description("A Minecraft Fabric Server")
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .build());

        app.synth();
    }
}

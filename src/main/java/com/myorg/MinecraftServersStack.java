package com.myorg;

import com.google.common.collect.ImmutableMap;
import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.Volume;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.efs.FileSystem;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneProviderProps;
import software.amazon.awscdk.services.route53.IHostedZone;

import java.util.List;
import java.util.Map;

/**
 * https://github.com/vatertime/minecraft-spot-pricing/blob/master/cf.yml
 * <p>
 * https://www.curseforge.com/minecraft/mc-mods/simple-voice-chat
 * https://www.curseforge.com/minecraft/mc-mods/falling-tree
 * https://www.curseforge.com/minecraft/mc-mods/identity
 * <p>
 * win: %appdata%/.minecraft
 */
public class MinecraftServersStack extends Stack {
    private static final boolean RUNNING = true;
    private static final int SIMPLE_VOICE_CHAT_PORT = 24454;
    private static final int MINECRAFT_GAME_PORT = 25565;
    private static final int EFS_NFS_PORT = 2049;
    private static final int SSH_PORT = 22;

    private static final String MY_IP = "173.180.99.99/32"; //Change this to your IP Address for SSH access
    private static final String ROUTE_53_DOMAIN = "yourdomain.com";
    private static final String ROUTE_53_SUBDOMAIN = "fabric." + ROUTE_53_DOMAIN;

    public MinecraftServersStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public MinecraftServersStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Vpc mcVpc = Vpc.Builder.create(this, "McVpc")
                .cidr("10.20.0.0/26")
                .enableDnsSupport(true)
                .enableDnsHostnames(true)
                .maxAzs(2)
                .subnetConfiguration(List.of(
                                SubnetConfiguration.builder()
                                        .name("mcSubnet")
                                        .subnetType(SubnetType.PUBLIC)
                                        .cidrMask(28)
                                        .build()
                        )
                )
                .build();

        SecurityGroup ec2Sg = SecurityGroup.Builder.create(this, "mcEc2Sg")
                .vpc(mcVpc)
                .description("Minecraft Server Security Group")
                .build();

        ec2Sg.addIngressRule(Peer.ipv4(MY_IP), Port.tcp(SSH_PORT), "Allow Ipv4 SSH access");
        ec2Sg.addIngressRule(Peer.anyIpv4(), Port.tcp(MINECRAFT_GAME_PORT), "Allow minecraft game port");
        ec2Sg.addIngressRule(Peer.anyIpv4(), Port.udp(SIMPLE_VOICE_CHAT_PORT), "Allow Simple Voice Chat comm");

        SecurityGroup efsSg = SecurityGroup.Builder.create(this, "mcEfsSg")
                .vpc(mcVpc)
                .description("EFS Security Group")
                .build();

        efsSg.addIngressRule(ec2Sg, Port.tcp(EFS_NFS_PORT), "Allow EFS Mounts");

        FileSystem mcDataFs = FileSystem.Builder.create(this, "mcDataFs")
                .vpc(mcVpc)
                .encrypted(false)
                .removalPolicy(RemovalPolicy.DESTROY) //TODO: Only during development
                .securityGroup(efsSg)
                .build();

        UserData userData = UserData.forLinux();
        userData.addCommands(
                "yum update -y",
                "yum install -y vim",
                "yum install -y amazon-efs-utils",
                "mkdir /opt/minecraft",
                "mount -t efs " + mcDataFs.getFileSystemId() + ":/ /opt/minecraft",
                "chown 845:845 /opt/minecraft"
        );

        IMachineImage ecsMachineImage = MachineImage.fromSsmParameter(
                "/aws/service/ecs/optimized-ami/amazon-linux-2/recommended/image_id",
                SsmParameterImageOptions.builder()
                        .cachedInContext(true)
                        .os(OperatingSystemType.LINUX)
                        .userData(userData)
                        .build()
        );

        int desiredCapacity = RUNNING ? 1 : 0;

        AutoScalingGroup singletonFleet = AutoScalingGroup.Builder.create(this, "mcSingletonFleet")
                .vpc(mcVpc)
                .desiredCapacity(desiredCapacity)
                .maxCapacity(1)
                .minCapacity(0)
                .associatePublicIpAddress(true)
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.LARGE))
                .machineImage(ecsMachineImage)
                .securityGroup(ec2Sg)
                .keyName("germer-play")
                .spotPrice("0.1")
                .build();

        singletonFleet.getNode().addDependency(mcDataFs.getMountTargetsAvailable());

        Cluster mcEcsCluster = Cluster.Builder.create(this, "mcEcsCluster")
                .vpc(mcVpc)
                .build();

        mcEcsCluster.getNode().addDependency(mcDataFs.getMountTargetsAvailable());
        mcEcsCluster.addAsgCapacityProvider(AsgCapacityProvider.Builder.create(this, "cp")
                .autoScalingGroup(singletonFleet).build());

        Volume dataVolume = Volume.builder()
                .name("minecraft-data")
                .host(Host.builder().sourcePath("/opt/minecraft").build())
                .build();

        Ec2TaskDefinition mcTaskDefinition = Ec2TaskDefinition.Builder.create(this, "mcTaskDefinition")
                .volumes(List.of(dataVolume))
                .build();

        ContainerDefinition container = mcTaskDefinition.addContainer("minecraft", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("itzg/minecraft-server:latest"))
                .memoryReservationMiB(1024 * 5)
                .portMappings(List.of(
                        PortMapping.builder()
                                .containerPort(MINECRAFT_GAME_PORT)
                                .hostPort(MINECRAFT_GAME_PORT)
                                .protocol(Protocol.TCP)
                                .build(),
                        PortMapping.builder()
                                .containerPort(SIMPLE_VOICE_CHAT_PORT)
                                .hostPort(SIMPLE_VOICE_CHAT_PORT)
                                .protocol(Protocol.UDP)
                                .build()
                ))
                .environment(ImmutableMap.<String, String>builder()
                        .put("EULA", "TRUE") // Must accept
                        .put("OPS", "CoolAxolotl") //Admin-player names
                        .put("TYPE", "FABRIC") // Minecraft server flavor
                        .put("DIFFICULTY", "normal")
                        //.put("WHITELIST", "")
                        //.put("VERSION", "")
                        .put("MEMORY", "5G")
                        // .put("SEED", "")
                        .put("MAX_PLAYERS", "10")
                        .put("VIEW_DISTANCE", "10")
                        .put("MODE", "survival")
                        .put("LEVEL_TYPE", "BIOMESOPLENTY")
                        .put("ENABLE_ROLLING_LOGS", "true")
                        .put("TZ", "America/Vancouver")
                        .build())
                .build());

        container.addMountPoints(MountPoint.builder()
                .containerPath("/data")
                .readOnly(false)
                .sourceVolume(dataVolume.getName())
                .build());

        Ec2Service minecraftServerService = Ec2Service.Builder.create(this, "mcServerService")
                .cluster(mcEcsCluster)
                .taskDefinition(mcTaskDefinition)
                .desiredCount(desiredCapacity)
                .maxHealthyPercent(100)
                .minHealthyPercent(0)
                .build();

        IHostedZone playWithLgZone = HostedZone.fromLookup(
                this,
                "playWithLgZone",
                HostedZoneProviderProps.builder().domainName(ROUTE_53_DOMAIN).build()
        );

        PolicyDocument dnsSetterPolicy = PolicyDocument.Builder.create()
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .resources(List.of("*"))
                                .actions(List.of("route53:*"))
                                .effect(Effect.ALLOW)
                                .build(),
                        PolicyStatement.Builder.create()
                                .resources(List.of("*"))
                                .actions(List.of("ec2:DescribeInstance*"))
                                .effect(Effect.ALLOW)
                                .build()
                )).build();

        Role dnsSetterLambdaRole = Role.Builder.create(this, "dnsSetterLambdaRole")
                .description("Allows dynamically setting Route 53 DNS and describing EC2 instances")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .inlinePolicies(Map.of("root", dnsSetterPolicy))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
//                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole")
                )).build();

        Function dnsSetterFunction = Function.Builder.create(this, "dnsSetter")
//                .vpc(mcVpc)
                .description("Sets Route 53 DNS Record for Minecraft Server")
                .memorySize(128)
                .runtime(Runtime.PYTHON_3_7)
                .timeout(Duration.seconds(20))
                .handler("index.handler")
                .environment(Map.of(
                        "RecordName", ROUTE_53_SUBDOMAIN,
                        "HostedZoneId", playWithLgZone.getHostedZoneId()
                ))
//                .allowPublicSubnet(true)
                .code(Code.fromInline(buildDnsSetterCode()))
                .role(dnsSetterLambdaRole)
                .build();

        Rule serverLaunchRule = Rule.Builder.create(this, "serverLaunchRule")
                .description("Trigger dnsSetter on EC2 instance launch")
                .eventPattern(EventPattern.builder()
                        .source(List.of("aws.autoscaling"))
                        .detailType(List.of("EC2 Instance Launch Successful"))
                        .detail(Map.of("AutoScalingGroupName", List.of(singletonFleet.getAutoScalingGroupName())))
                        .build())
                .enabled(true)
                .targets(List.of(new LambdaFunction(dnsSetterFunction)))
                .build();

    }


    private String buildDnsSetterCode() {
        return "import boto3\n" +
                "import os\n" +
                "def handler(event, context):\n" +
                "  new_instance = boto3.resource('ec2').Instance(event['detail']['EC2InstanceId'])\n" +
                "  boto3.client('route53').change_resource_record_sets(\n" +
                "    HostedZoneId= os.environ['HostedZoneId'],\n" +
                "    ChangeBatch={\n" +
                "        'Comment': 'updating',\n" +
                "        'Changes': [\n" +
                "            {\n" +
                "                'Action': 'UPSERT',\n" +
                "                'ResourceRecordSet': {\n" +
                "                    'Name': os.environ['RecordName'],\n" +
                "                    'Type': 'A',\n" +
                "                    'TTL': 60,\n" +
                "                    'ResourceRecords': [\n" +
                "                        {\n" +
                "                            'Value': new_instance.public_ip_address\n" +
                "                        },\n" +
                "                    ]\n" +
                "                }\n" +
                "            },\n" +
                "        ]\n" +
                "    })";
    }
}

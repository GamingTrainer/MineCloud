/*
 * Copyright (c) 2015, Mazen Kotb <email@mazenmc.io>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package io.minecloud.daemon;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.messages.*;
import io.minecloud.MineCloud;
import io.minecloud.db.Credentials;
import io.minecloud.models.bungee.Bungee;
import io.minecloud.models.bungee.BungeeRepository;
import io.minecloud.models.bungee.type.BungeeType;
import io.minecloud.models.network.Network;
import io.minecloud.models.nodes.Node;
import io.minecloud.models.server.Server;
import io.minecloud.models.server.ServerRepository;
import io.minecloud.models.server.World;
import io.minecloud.models.server.type.ServerType;
import org.apache.logging.log4j.Level;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public final class Deployer {

    private Deployer() {}

    public static void deployServer(Network network, ServerType type) {
        ServerRepository repository = MineCloud.instance().mongo().repositoryBy(Server.class);
        Server server = new Server();

        server.setNumber(repository.highestNumberFor(type) + 1);
        server.setNetwork(network);
        server.setNode(MineCloudDaemon.instance().node());
        server.setOnlinePlayers(new ArrayList<>());
        server.setRamUsage(-1);
        server.setPort(0);

        deployServer(server);
        repository.save(server);
    }

    public static void deployServer(Server server) {
        Credentials mongoCreds = MineCloud.instance().mongo().credentials();
        Credentials redisCreds = MineCloud.instance().redis().credentials();
        World defaultWorld = server.type().defaultWorld();
        ContainerConfig config = ContainerConfig.builder()
                .image("minecloud/server")
                .openStdin(true)
                .env(new EnvironmentBuilder()
                        .append("mongo_hosts", mongoCreds.formattedHosts())
                        .append("mongo_username", mongoCreds.username())
                        .append("mongo_password", new String(mongoCreds.password()))
                        .append("mongo_database", mongoCreds.database())

                        .append("redis_host", redisCreds.hosts()[0])
                        .append("redis_username", redisCreds.username())
                        .append("redis_password", new String(redisCreds.password()))

                        .append("server_id", server.entityId())
                        .append("DEFAULT_WORLD", defaultWorld.name())
                        .append("DEFAULT_WORLD_VERSION", defaultWorld.version())
                        .build())
                .build();

        ContainerCreation creation;

        try {
            DockerClient client = MineCloudDaemon.instance().dockerClient();
            creation = client.createContainer(config, server.type().name() + server.number());

            client.startContainer(creation.id(), HostConfig.builder()
                    .binds("/mnt/minecloud:/mnt/minecloud")
                    .build());
        } catch (InterruptedException | DockerException ex) {
            MineCloud.logger().log(Level.ERROR, "Was unable to create server with type " + server.type().name(),
                    ex);
            return;
        }

        MineCloud.logger().info("Started server " + server.name()
                + " with container id " + server.containerId());
    }

    public static void deployBungee(Network network, BungeeType type) {
        BungeeRepository repository = MineCloud.instance().mongo().repositoryBy(Bungee.class);
        Node node = MineCloudDaemon.instance().node();
        Bungee bungee = new Bungee();

        bungee.setId(new ObjectId().toString());

        Credentials mongoCreds = MineCloud.instance().mongo().credentials();
        Credentials redisCreds = MineCloud.instance().redis().credentials();
        ContainerConfig config = ContainerConfig.builder()
                .image("minecloud/bungee")
                .exposedPorts("25565")
                .openStdin(true)
                .env(new EnvironmentBuilder()
                        .append("mongo_hosts", mongoCreds.formattedHosts())
                        .append("mongo_username", mongoCreds.username())
                        .append("mongo_password", new String(mongoCreds.password()))
                        .append("mongo_database", mongoCreds.database())

                        .append("redis_host", redisCreds.hosts()[0])
                        .append("redis_username", redisCreds.username())
                        .append("redis_password", new String(redisCreds.password()))

                        .append("bungee_id", bungee.entityId())
                        .build())
                .build();
        HostConfig hostConfig = HostConfig.builder()
                .binds("/mnt/minecloud:/mnt/minecloud")
                .portBindings(new HashMap<String, List<PortBinding>>() {{
                    put(node.privateIp(), Arrays.asList(PortBinding.of(node.publicIp(), 25565))); // I'm sorry
                }})
                .build();

        ContainerCreation creation;

        try {
            DockerClient client = MineCloudDaemon.instance().dockerClient();
            creation = client.createContainer(config, type.name());

            client.startContainer(creation.id(), hostConfig);
        } catch (InterruptedException | DockerException ex) {
            MineCloud.logger().log(Level.ERROR, "Was unable to create bungee with type " + type.name(),
                    ex);
            return;
        }

        bungee.setNetwork(network);
        bungee.setId(creation.id());
        bungee.setNode(node);
        bungee.setPublicIp(node.publicIp());
        bungee.setType(type);
        bungee.setRamUsage(-1);

        repository.save(bungee);
        MineCloud.logger().info("Started bungee " + bungee.name() + " with container id " + bungee.containerId());
    }

    private static class EnvironmentBuilder {
        private List<String> environmentVars = new ArrayList<>();

        private EnvironmentBuilder() {
        }

        public EnvironmentBuilder append(String key, String value) {
            environmentVars.add(key + "=" + value);
            return this;
        }

        public String[] build() {
            return environmentVars.stream().toArray(String[]::new);
        }
    }
}

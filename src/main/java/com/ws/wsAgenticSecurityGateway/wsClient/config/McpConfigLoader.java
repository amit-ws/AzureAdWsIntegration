package com.ws.wsAgenticSecurityGateway.wsClient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Slf4j
public class McpConfigLoader {

    private final ObjectMapper objectMapper;

    @Value("${mcp.config.file:mcp_config.json}")
    private String configFileName;

    @Value("${mcp.config.directory:${user.home}/.config/mcp}")
    private String configDirectory;

    public McpConfigLoader() {
        this.objectMapper = new ObjectMapper();
    }

    public McpConfigFile loadConfig() throws IOException {

        var classpathResource = getClass().getClassLoader().getResource(configFileName);
        if (classpathResource != null) {
 log.info("Loading MCP config from classpath: {}", configFileName);
            try (var inputStream = getClass().getClassLoader().getResourceAsStream(configFileName)) {
                if (inputStream != null) {
                    String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    return parseConfig(content, "classpath:" + configFileName);
                }
            } catch (IOException e) {
                log.warn("Failed to load from classpath, trying other locations: {}", e.getMessage());
            }
        }

        File configFile = findConfigFile();

        if (configFile == null || !configFile.exists()) {
 log.error("MCP config file not found. Searched locations:");
            log.error("1. Classpath: {}", configFileName);
            if (configDirectory != null && !configDirectory.isEmpty()) {
                log.error("2. Directory: {}/{}", configDirectory, configFileName);
            }
            log.error("3. Current dir: ./{}", configFileName);
            throw new IOException("MCP configuration file not found: " + configFileName);
        }

 log.info("Loading MCP config from: {}", configFile.getAbsolutePath());

        if (!configFile.canRead()) {
            throw new IOException("Cannot read config file: " + configFile.getAbsolutePath());
        }

        String content = Files.readString(configFile.toPath());
        return parseConfig(content, configFile.getAbsolutePath());
    }

    private McpConfigFile parseConfig(String content, String source) throws IOException {
        log.debug("Config file content length: {} bytes", content.length());

        try {
            McpConfigFile config = objectMapper.readValue(content, McpConfigFile.class);

            config.validate();

 log.info("Successfully loaded {} MCP server configurations from {}",
                    config.getMcpServers().size(), source);

            config.getMcpServers().keySet().forEach(name ->
                    log.info("- {}", name)
            );

            if (config.getInputs() != null && !config.getInputs().isEmpty()) {
 log.info("Found {} input variables", config.getInputs().size());
                config.getInputs().keySet().forEach(key ->
                        log.debug("- {}", key)
                );
            }

            return config;

        } catch (IOException e) {
 log.error("Failed to parse MCP config file: {}", e.getMessage());
            throw new IOException("Invalid MCP configuration format: " + e.getMessage(), e);
        }
    }

    public ConfigVariableResolver createResolver(McpConfigFile config) {
        return new ConfigVariableResolver(config.getInputs());
    }

    private File findConfigFile() {
        try {
            var resource = getClass().getClassLoader().getResource(configFileName);
            if (resource != null) {
                log.debug("Found config in classpath: {}", resource.getPath());
                if (resource.getProtocol().equals("file")) {
                    File file = new File(resource.toURI());
                    if (file.exists()) {
                        log.debug("Config file accessible as File: {}", file.getAbsolutePath());
                        return file;
                    }
                }
                log.debug("Config found in classpath but not as file, will use input stream");
                return null;
            }
        } catch (Exception e) {
            log.debug("Config not in classpath: {}", e.getMessage());
        }

        if (configDirectory != null && !configDirectory.isEmpty()) {
            Path configDirPath = Paths.get(configDirectory.replace("${user.home}",
                    System.getProperty("user.home")));
            File configDirFile = configDirPath.resolve(configFileName).toFile();
            if (configDirFile.exists()) {
                log.debug("Found config in directory: {}", configDirFile.getAbsolutePath());
                return configDirFile;
            }
        }

        File currentDirFile = new File(configFileName);
        if (currentDirFile.exists()) {
            log.debug("Found config in current directory: {}", currentDirFile.getAbsolutePath());
            return currentDirFile;
        }

        return null;
    }

    public McpConfigFile reloadConfig() throws IOException {
 log.info("Reloading MCP configuration...");
        return loadConfig();
    }
}

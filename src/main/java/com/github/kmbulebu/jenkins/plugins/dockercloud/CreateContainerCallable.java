package com.github.kmbulebu.jenkins.plugins.dockercloud;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.ListContainersParam;
import com.spotify.docker.client.ImageNotFoundException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;

import hudson.model.Node;
import hudson.model.Slave;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

public class CreateContainerCallable implements Callable<Node> {
	
	private static final Logger LOGGER = Logger.getLogger(CreateContainerCallable.class.getName());
	
	// Multiply the two for the time in ms we wait for the container to start.
	private static final int CONTAINER_START_WAIT_INTERVAL_MS = 1000;
	private static final int CONTAINER_START_WAIT_MAX_COUNT = 60;
	
	private final String name;
	
	private final DockerCloud dockerCloud;
	private final DockerImage dockerImage;

	public CreateContainerCallable(DockerCloud dockerCloud, DockerImage dockerImage) {
		super();
		this.name = UUID.randomUUID().toString();
		this.dockerCloud = dockerCloud;
		this.dockerImage = dockerImage;
	}
	
	private String getNodeDescription() {
		return "Docker container built from image config '" + dockerImage.getName() + "' using docker image '" + dockerImage.getDockerImageName() + "' running in the '" + dockerCloud.getDisplayName() + "' docker cloud.";
	}

	@Override
	public Node call() throws Exception {
		LOGGER.fine("Creating docker slave node with name " + name);
		final DockerSlave slave = new DockerSlave(dockerCloud, name, getNodeDescription(), dockerImage.getRemoteFS(), dockerImage.getMode(), dockerImage.getLabelString());
		final DockerClient docker = dockerCloud.getDockerClient();
		
	    // Pull image.
		boolean imageExists;
		try {
			LOGGER.fine("Checking if image " + dockerImage.getDockerImageName() + " exists.");
			if (docker.inspectImage(dockerImage.getDockerImageName()) != null) {
				imageExists = true;
			} else {
				// Should be unreachable.
				imageExists = false;
			}
		} catch (ImageNotFoundException e) {
			imageExists = false;
		}
		
		LOGGER.fine("Image " + dockerImage + " exists? " + imageExists + ", Pull disabled? " + dockerImage.isDisablePull());
		
		if (!imageExists) {
			if (dockerImage.isDisablePull()) {
				throw new IllegalStateException("Image '" + dockerImage.getDockerImageName() + "' does not exist on Docker cloud '" + dockerCloud.getDisplayName() + "' and pull is disabled.");
			} 
			LOGGER.info("Pulling image " + dockerImage.getDockerImageName() + ".");
			docker.pull(dockerImage.getDockerImageName());
			LOGGER.fine("Finished pulling image " + dockerImage.getDockerImageName() + ".");
		} 
		
		// This ensures a Computer is created so that the slave url is available.
		Jenkins.getInstance().addNode(slave);
		
		// Create and start container
		final String[] command = new String[] {"sh", "-c", "curl -o slave.jar " + getSlaveJarUrl() + " && java -jar slave.jar -jnlpUrl " + getSlaveJnlpUrl(slave)};
		ContainerConfig containerConfig = ContainerConfig.builder().image(dockerImage.getDockerImageName()).cmd(command).build();
		LOGGER.info("Creating container " + name + " from image " + dockerImage.getDockerImageName() + ".");
		ContainerCreation creation = docker.createContainer(containerConfig, name);
		slave.setDockerId(creation.id());
		LOGGER.info("Starting container " + name + " with id " + creation.id() + ".");
		docker.startContainer(creation.id());
		
		// Wait for Jenkins to get Computer via Launcher online
		int elapsed = 0;
        do {
            Thread.sleep(CONTAINER_START_WAIT_INTERVAL_MS);
            elapsed++;
            LOGGER.info("Waiting for slave on container " + name + " with id " + creation.id() + "...");
        } while (slave.getComputer() != null && !slave.getComputer().isOnline() && elapsed < CONTAINER_START_WAIT_MAX_COUNT);
        
        if (slave.getComputer() == null) {
        	LOGGER.info("slave.getComputer() is null for container " + name + " with id " + creation.id() + ".");
            throw new IllegalStateException("Node was deleted, computer is null");
        }	
        
        if (!slave.getComputer().isOnline()) {
        	LOGGER.info("Slave is not online yet for container " + name + " with id " + creation.id() + ". Giving up.");
            throw new IllegalStateException("Timed out waiting for slave container to come online.");
        }
        
        // Make sure JNLP is connected before returning our slave.
        slave.toComputer().connect(false);

        return slave;
	}
	
	/*
	 *  Get a Jenkins Base URL ending with /
	 */
	private String getJenkinsBaseUrl() {
		String url = JenkinsLocationConfiguration.get().getUrl();
		if (url.endsWith("/")) {
			return url;
		} else {
			return url + '/';
		}
	}
	
	/*
	 * Get the slave jar URL.
	 */
	private String getSlaveJarUrl() {
		return getJenkinsBaseUrl() + "jnlpJars/slave.jar";
	}
	
	/*
	 * Get the JNLP URL for the slave.
	 */
	private String getSlaveJnlpUrl(Slave slave) {
		return getJenkinsBaseUrl() + slave.getComputer().getUrl() + "slave-agent.jnlp";
		
	}

}

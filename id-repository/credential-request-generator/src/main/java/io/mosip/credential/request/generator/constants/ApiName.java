package io.mosip.credential.request.generator.constants;

/**
 * 
 * @author Sowmya
 *
 */
public enum ApiName {

	ENCRYPTION("ENCRYPTION"),
	DECRYPTION("DECRYPTION"),
	CRDENTIALSERVICE("CRDENTIALSERVICE"),
	KERNELAUDITMANAGER("mosip.idrepo.audit.rest.uri");

	private final String serviceName;

	private ApiName(String serviceName) {
		this.serviceName = serviceName;
	}

	private ApiName() {
		this.serviceName = "";
	}

	public String getServiceName() {
		return serviceName;
	}
}

package io.mosip.credentialstore.dto;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

@Data
public class PartnerExtractorResponse implements Serializable {
	
	private static final long serialVersionUID = 1L;
	
	List<PartnerExtractor> extractors;

}

package io.mosip.idrepository.identity.service.impl;

import static io.mosip.idrepository.core.constant.HandleStatusLifecycle.DELETE;
import static io.mosip.idrepository.core.constant.IdRepoConstants.*;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Resource;

import io.mosip.idrepository.core.entity.Handle;
import io.mosip.idrepository.core.repository.HandleRepo;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.skyscreamer.jsonassert.FieldComparisonFailure;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import io.mosip.idrepository.core.constant.CredentialRequestStatusLifecycle;
import io.mosip.idrepository.core.constant.HandleStatusLifecycle;
import io.mosip.idrepository.core.constant.IdType;
import io.mosip.idrepository.core.dto.*;
import io.mosip.idrepository.core.entity.CredentialRequestStatus;
import io.mosip.idrepository.core.exception.IdRepoAppException;
import io.mosip.idrepository.core.exception.IdRepoAppUncheckedException;
import io.mosip.idrepository.core.logger.IdRepoLogger;
import io.mosip.idrepository.core.repository.CredentialRequestStatusRepo;
import io.mosip.idrepository.core.repository.UinEncryptSaltRepo;
import io.mosip.idrepository.core.repository.UinHashSaltRepo;
import io.mosip.idrepository.core.security.IdRepoSecurityManager;
import io.mosip.idrepository.core.spi.IdRepoService;
import io.mosip.idrepository.core.util.DummyPartnerCheckUtil;
import io.mosip.idrepository.core.util.EnvUtil;
import io.mosip.idrepository.identity.dto.HandleDto;
import io.mosip.idrepository.identity.entity.*;
import io.mosip.idrepository.identity.helper.AnonymousProfileHelper;
import io.mosip.idrepository.identity.helper.IdRepoServiceHelper;
import io.mosip.idrepository.identity.helper.ObjectStoreHelper;
import io.mosip.idrepository.identity.provider.IdentityUpdateTrackerPolicyProvider;
import io.mosip.idrepository.identity.repository.*;
import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.spi.CbeffUtil;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.UUIDUtils;

/**
 * The Class IdRepoServiceImpl - Service implementation for Identity service.
 */
@Component
@Primary
@Transactional(rollbackFor = { IdRepoAppException.class, IdRepoAppUncheckedException.class })
public class IdRepoServiceImpl<T> implements IdRepoService<IdRequestDTO<T>, Uin> {

	private static final String VERIFIED_ATTRIBUTES = "verifiedAttributes";

	/** The Constant UPDATE_IDENTITY. */
	private static final String UPDATE_IDENTITY = "updateIdentity";

	/** The Constant ROOT. */
	private static final String ROOT = "$";

	/** The Constant OPEN_SQUARE_BRACE. */
	private static final String OPEN_SQUARE_BRACE = "[";

	/** The Constant LANGUAGE. */
	private static final String LANGUAGE = "language";

	private static final String VALUE = "value";

	/** The Constant ADD_IDENTITY. */
	private static final String ADD_IDENTITY = "addIdentity";

	private static final String ADD_IDENTITY_HANDLE = "addIdentityHandle";

	private static final String COMMA = ",";

	/** The mosip logger. */
	Logger mosipLogger = IdRepoLogger.getLogger(IdRepoServiceImpl.class);

	/** The Constant TYPE. */
	private static final String TYPE = "type";

	/** The Constant DOT. */
	private static final String DOT = ".";

	/** The Constant ID_REPO_SERVICE_IMPL. */
	private static final String ID_REPO_SERVICE_IMPL = "IdRepoServiceImpl";
	
	private static final String RETRIEVE_IDENTITY = "retrieveIdentity";

	/** The env. */
	@Autowired
	protected EnvUtil env;

	/** The mapper. */
	@Autowired
	protected ObjectMapper mapper;

	/** The uin repo. */
	@Autowired
	protected UinRepo uinRepo;

	/** The uin detail repo. */
	@Autowired
	private UinDocumentHistoryRepo uinDocHRepo;

	/** The uin bio H repo. */
	@Autowired
	private UinBiometricHistoryRepo uinBioHRepo;

	/** The uin history repo. */
	@Autowired
	protected UinHistoryRepo uinHistoryRepo;

	/** The cbeff util. */
	@Autowired
	protected CbeffUtil cbeffUtil;

	/** The security manager. */
	@Autowired
	protected IdRepoSecurityManager securityManager;

	/** The bio attributes. */
	@Resource
	protected List<String> bioAttributes;

	/** The uin hash salt repo. */
	@Autowired
	protected UinHashSaltRepo uinHashSaltRepo;

	/** The uin encrypt salt repo. */
	@Autowired
	protected UinEncryptSaltRepo uinEncryptSaltRepo;

	@Autowired
	protected ObjectStoreHelper objectStoreHelper;

	@Autowired
	private DummyPartnerCheckUtil dummyPartner;

	@Autowired
	private CredentialRequestStatusRepo credRequestRepo;
	
	@Autowired
	protected AnonymousProfileHelper anonymousProfileHelper;
	
	@Value("${mosip.idrepo.identity.uin-status.registered}")
	private String activeStatus;
	
	@Autowired
	private IdentityUpdateTrackerRepo identityUpdateTracker;

	@Value("${mosip.idrepo.credential.request.enable-convention-based-id:false}")
	private boolean enableConventionBasedId;

	@Autowired
	private HandleRepo handleRepo;

	@Autowired
	private IdRepoServiceHelper idRepoServiceHelper;

	@Value("${" + UIN_REFID + "}")
	private String uinRefId;

	@Value("${mosip.idrepo.update-identity.trim-whitespaces:true}")
	private boolean trimWhitespaces;

	@Value("#{${mosip.idrepo.update-identity.fields-to-replace}}")
	private List<String> fieldsToReplaceOnUpdate;


	/**
	 * Adds the identity to DB.
	 *
	 * @param request the request
	 * @param uin     the uin
	 * @return the uin
	 * @throws IdRepoAppException the id repo app exception
	 */
	@Override
	public Uin addIdentity(IdRequestDTO<T> request, String uin) throws IdRepoAppException {
		long epoch = System.currentTimeMillis();
		String uinRefId = UUIDUtils.getUUID(UUIDUtils.NAMESPACE_OID, uin + SPLITTER + DateUtils.getUTCCurrentDateTime())
				.toString();
		ObjectNode identityObject = mapper.convertValue(request.getIdentity(), ObjectNode.class);
		identityObject.putPOJO(VERIFIED_ATTRIBUTES, request.getVerifiedAttributes());
		byte[] identityInfo = convertToBytes(identityObject);
		String uinHash = getUinHash(uin);
		String uinHashWithSalt = uinHash.split(SPLITTER)[1];
		String uinToEncrypt = getUinToEncrypt(uin);

		mosipLogger.info("Before starting the checkAndGetHandles: {}", System.currentTimeMillis()-epoch);
		epoch = System.currentTimeMillis();

		Map<String, List<HandleDto>> selectedUniqueHandlesMap = checkAndGetHandles(request, null, null, CREATE);

		mosipLogger.info("After completing with checkAndGetHandles: {}", System.currentTimeMillis()-epoch);
		epoch = System.currentTimeMillis();

		anonymousProfileHelper
			.setRegId(request.getRegistrationId())
			.setNewUinData(identityInfo);

		List<UinDocument> docList = new ArrayList<>();
		List<UinBiometric> bioList = new ArrayList<>();
		Uin uinEntity;
		if (Objects.nonNull(request.getDocuments()) && !request.getDocuments().isEmpty()) {
			addDocuments(uinHashWithSalt, identityInfo, request.getDocuments(), uinRefId, docList, bioList,
					false);
			uinEntity = new Uin(uinRefId, uinToEncrypt, uinHash, identityInfo, securityManager.hash(identityInfo),
					request.getRegistrationId(), activeStatus, IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), null, null, false, null, bioList, docList);
			uinEntity = uinRepo.save(uinEntity);
			mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, ADD_IDENTITY,
					"Record successfully saved in db with documents");
		} else {
			uinEntity = new Uin(uinRefId, uinToEncrypt, uinHash, identityInfo, securityManager.hash(identityInfo),
					request.getRegistrationId(), activeStatus, IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), null, null, false, null, null, null);
			uinEntity = uinRepo.save(uinEntity);
			mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, ADD_IDENTITY,
					"Record successfully saved in db without documents");
		}

		uinHistoryRepo.save(new UinHistory(uinRefId, DateUtils.getUTCCurrentDateTime(), uinEntity.getUin(), uinEntity.getUinHash(),
						uinEntity.getUinData(), uinEntity.getUinDataHash(), uinEntity.getRegId(), activeStatus,
						IdRepoSecurityManager.getUser(), DateUtils.getUTCCurrentDateTime(), null, null, false, null));

		mosipLogger.info("After adding UIN: {}", System.currentTimeMillis()-epoch);
		epoch = System.currentTimeMillis();

		addIdentityHandle(uinEntity, selectedUniqueHandlesMap);

		mosipLogger.info("After addIdentityHandle: {}", System.currentTimeMillis()-epoch);
		epoch = System.currentTimeMillis();

		issueCredential(uinEntity.getUin(), uinHashWithSalt, activeStatus, null, uinEntity.getRegId());

		mosipLogger.info("After issueCredential: {}", System.currentTimeMillis()-epoch);
		epoch = System.currentTimeMillis();

		anonymousProfileHelper.buildAndsaveProfile(false);
		mosipLogger.info("After buildAndsaveProfile: {}", System.currentTimeMillis()-epoch);
		return uinEntity;
	}

	protected String getUinToEncrypt(String uin) {
		int modResult = securityManager.getSaltKeyForId(uin);
		String encryptSalt = uinEncryptSaltRepo.retrieveSaltById(modResult);
		return modResult + SPLITTER + uin + SPLITTER + encryptSalt;
	}

	protected String getUinHash(String uin) {
		int modResult = securityManager.getSaltKeyForId(uin);
		String hashSalt = uinHashSaltRepo.retrieveSaltById(modResult);
		return modResult + SPLITTER + securityManager.hashwithSalt(uin.getBytes(), hashSalt.getBytes());
	}

	/**
	 * Stores the documents to FileSystem.
	 *
	 * @param uinHash      the uin hash
	 * @param identityInfo the identity info
	 * @param documents    the documents
	 * @param uinRefId     the uin ref id
	 * @param docList      the doc list
	 * @param bioList      the bio list
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void addDocuments(String uinHash, byte[] identityInfo, List<DocumentsDTO> documents, String uinRefId,
			List<UinDocument> docList, List<UinBiometric> bioList, boolean isDraft) {
		ObjectNode identityObject = convertToObject(identityInfo, ObjectNode.class);
		IntStream.range(0, documents.size()).filter(index -> identityObject.has(documents.get(index).getCategory())).forEach(index -> {
			DocumentsDTO doc = documents.get(index);
			JsonNode docType = identityObject.get(doc.getCategory());
			try {
				if (bioAttributes.contains(doc.getCategory())) {
					addBiometricDocuments(uinHash, uinRefId, bioList, doc, docType, isDraft, index);
					anonymousProfileHelper.setNewCbeff(doc.getValue());
				} else {
					addDemographicDocuments(uinHash, uinRefId, docList, doc, docType, isDraft);
				}
			} catch (IdRepoAppException e) {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, ADD_IDENTITY, e.getMessage());
				throw new IdRepoAppUncheckedException(e.getErrorCode(), e.getErrorText(), e);
			}
		});
	}

	/**
	 * Stores the biometric documents to FileSystem.
	 *
	 * @param uinHash  the uin hash
	 * @param uinRefId the uin ref id
	 * @param bioList  the bio list
	 * @param doc      the doc
	 * @param docType  the doc type
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void addBiometricDocuments(String uinHash, String uinRefId, List<UinBiometric> bioList, DocumentsDTO doc,
			JsonNode docType, boolean isDraft, int index) throws IdRepoAppException {
		byte[] data = null;
		String fileRefId = UUIDUtils
				.getUUID(UUIDUtils.NAMESPACE_OID,
						docType.get(FILE_NAME_ATTRIBUTE).asText() + SPLITTER + DateUtils.getUTCCurrentDateTime())
				.toString() + DOT + docType.get(FILE_FORMAT_ATTRIBUTE).asText();
		long decodeStartTime = System.currentTimeMillis();
		data = CryptoUtil.decodeURLSafeBase64(doc.getValue());
		mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addBiometricDocuments",
				"Total time taken to decode CBEFF " + uinRefId + " (" + (System.currentTimeMillis() - decodeStartTime) + "ms)");
		try {
			decodeStartTime = System.currentTimeMillis();
			cbeffUtil.validateXML(data);
			mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addBiometricDocuments",
					"Total time taken to validate CBEFF " + uinRefId + " (" + (System.currentTimeMillis() - decodeStartTime) + "ms)");
		} catch (Exception e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addBiometricDocuments", e.getMessage());
			throw new IdRepoAppUncheckedException(INVALID_INPUT_PARAMETER.getErrorCode(),
					String.format(INVALID_INPUT_PARAMETER.getErrorMessage(), "documents/" + index + "/value"), e);
		}
		long s3StartTime = System.currentTimeMillis();
		objectStoreHelper.putBiometricObject(uinHash, fileRefId, data);
		mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addBiometricDocuments",
				"Total time taken to put biometric data into S3 " + uinRefId + " (" + (System.currentTimeMillis() - s3StartTime) + "ms)");
		bioList.add(new UinBiometric(uinRefId, fileRefId, doc.getCategory(), docType.get(FILE_NAME_ATTRIBUTE).asText(),
				securityManager.hash(data), "", IdRepoSecurityManager.getUser(),
				DateUtils.getUTCCurrentDateTime(), null, null, false, null));

		if (!isDraft) {
			long hRepoStartTime = System.currentTimeMillis();
			uinBioHRepo.save(new UinBiometricHistory(uinRefId, DateUtils.getUTCCurrentDateTime(), fileRefId, doc.getCategory(),
					docType.get(FILE_NAME_ATTRIBUTE).asText(), securityManager.hash(doc.getValue().getBytes()),
					"", IdRepoSecurityManager.getUser(), DateUtils.getUTCCurrentDateTime(),
					null, null, false, null));
			mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addBiometricDocuments",
					"Total time taken to save biometric data into DB " + uinRefId + " (" + (System.currentTimeMillis() - hRepoStartTime) + "ms)");
		}

	}

	/**
	 * Stores the demographic documents to FileSystem.
	 *
	 * @param uinHash  the uin hash
	 * @param uinRefId the uin ref id
	 * @param docList  the doc list
	 * @param doc      the doc
	 * @param docType  the doc type
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void addDemographicDocuments(String uinHash, String uinRefId, List<UinDocument> docList, DocumentsDTO doc,
			JsonNode docType, boolean isDraft) throws IdRepoAppException {
		String fileRefId = UUIDUtils
				.getUUID(UUIDUtils.NAMESPACE_OID,
						docType.get(FILE_NAME_ATTRIBUTE).asText() + SPLITTER + DateUtils.getUTCCurrentDateTime())
				.toString() + DOT + docType.get(FILE_FORMAT_ATTRIBUTE).asText();
		long decodeStartTime = System.currentTimeMillis();
		byte[] data = CryptoUtil.decodeURLSafeBase64(doc.getValue());
		mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addDemographicDocuments",
				"Total time taken to decode demographic doc into DB " + uinRefId + " (" + (System.currentTimeMillis() - decodeStartTime) + "ms)");

		long s3StartTime = System.currentTimeMillis();
		objectStoreHelper.putDemographicObject(uinHash, fileRefId, data);
		mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addDemographicDocuments",
				"Total time taken to put demographic doc into S3 " + uinRefId + " (" + (System.currentTimeMillis() - s3StartTime) + "ms)");

		docList.add(new UinDocument(uinRefId, doc.getCategory(), docType.get(TYPE).asText(), fileRefId,
				docType.get(FILE_NAME_ATTRIBUTE).asText(), docType.get(FILE_FORMAT_ATTRIBUTE).asText(),
				securityManager.hash(data), "", IdRepoSecurityManager.getUser(),
				DateUtils.getUTCCurrentDateTime(), null, null, false, null));

		if (!isDraft) {
			long hStartTime = System.currentTimeMillis();
			uinDocHRepo.save(new UinDocumentHistory(uinRefId, DateUtils.getUTCCurrentDateTime(), doc.getCategory(),
					docType.get(TYPE).asText(), fileRefId, docType.get(FILE_NAME_ATTRIBUTE).asText(),
					docType.get(FILE_FORMAT_ATTRIBUTE).asText(), securityManager.hash(data),
					"", IdRepoSecurityManager.getUser(), DateUtils.getUTCCurrentDateTime(),
					null, null, false, null));
			mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addDemographicDocuments",
					"Total time taken to save demographic doc into DB " + uinRefId + " (" + (System.currentTimeMillis() - hStartTime) + "ms)");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.idrepository.core.spi.IdRepoService#retrieveIdentity(java.lang.
	 * String, io.mosip.idrepository.core.constant.IdType, java.lang.String)
	 */
	@Override
	public Uin retrieveIdentity(String uinHash, IdType idType, String type, Map<String, String> extractionFormats)
			throws IdRepoAppException {
		Optional<Uin> uinObjOptional = uinRepo.findByUinHash(uinHash);
		if (uinObjOptional.isPresent()) {
			return uinObjOptional.get();
		} else {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, RETRIEVE_IDENTITY,
					NO_RECORD_FOUND.getErrorMessage());
			throw new IdRepoAppException(NO_RECORD_FOUND);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.kernel.core.idrepo.spi.IdRepoService#updateIdentity(java.lang.
	 * Object, java.lang.String)
	 */
	@Override
	public Uin updateIdentity(IdRequestDTO<T> request, String uin) throws IdRepoAppException {
		anonymousProfileHelper.setRegId(request.getRegistrationId());
		String uinHash = getUinHash(uin);
		String uinHashWithSalt = uinHash.split(SPLITTER)[1];
		try {
			updateRequestBodyData(request);
			Map<String, List<HandleDto>> inputSelectedHandlesMap = null;
			Uin uinObject = retrieveIdentity(uinHash, IdType.UIN, null, null);
			anonymousProfileHelper.setOldCbeff(uinHash,
					!anonymousProfileHelper.isOldCbeffPresent() && Objects.nonNull(uinObject.getBiometrics())
							&& !uinObject.getBiometrics().isEmpty()
									? uinObject.getBiometrics().get(uinObject.getBiometrics().size() - 1).getBioFileId()
									: null);
			uinObject.setRegId(request.getRegistrationId());
			if (Objects.nonNull(request.getStatus())
					&& !StringUtils.equals(uinObject.getStatusCode(), request.getStatus())) {
				uinObject.setStatusCode(request.getStatus());
				uinObject.setUpdatedBy(IdRepoSecurityManager.getUser());
				uinObject.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());
			}
			if (Objects.nonNull(request) && Objects.nonNull(request.getIdentity())) {
				inputSelectedHandlesMap = getNewAndDeleteExistingHandles(request, uinObject, UPDATE);
				Configuration configuration = Configuration.builder().jsonProvider(new JacksonJsonProvider())
						.mappingProvider(new JacksonMappingProvider()).build();
				DocumentContext inputData = JsonPath.using(configuration).parse(request.getIdentity());
				DocumentContext dbData = JsonPath.using(configuration).parse(new String(uinObject.getUinData()));
				anonymousProfileHelper.setOldUinData(dbData.jsonString().getBytes());
				updateVerifiedAttributes(request, inputData, dbData);
				replaceConfiguredFieldsOnUpdate(inputData, dbData);
				//TODO We should remove below json comparison as update operation always replaces the existing with new value
				JSONCompareResult comparisonResult = JSONCompare.compareJSON(inputData.jsonString(),
						dbData.jsonString(), JSONCompareMode.LENIENT);

				if (comparisonResult.failed()) {
					updateJsonObject(uinHash, inputData, dbData, comparisonResult, true);
				}
				uinObject.setUinData(convertToBytes(convertToObject(dbData.jsonString().getBytes(), Map.class)));
				uinObject.setUinDataHash(securityManager.hash(uinObject.getUinData()));
				uinObject.setUpdatedBy(IdRepoSecurityManager.getUser());
				uinObject.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());

				if (Objects.nonNull(request.getDocuments()) && !request.getDocuments().isEmpty()) {
					anonymousProfileHelper
							.setNewCbeff(uinObject.getUinHash(),
									!anonymousProfileHelper.isNewCbeffPresent() ?
									uinObject.getBiometrics().get(uinObject.getBiometrics().size() - 1).getBioFileId()
											: null);
					updateDocuments(uinHashWithSalt, uinObject, request, false);
					uinObject.setUpdatedBy(IdRepoSecurityManager.getUser());
					uinObject.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());
				}
			}

			uinObject = uinRepo.save(uinObject);
			anonymousProfileHelper.setNewUinData(uinObject.getUinData());
			uinHistoryRepo.save(new UinHistory(uinObject.getUinRefId(), DateUtils.getUTCCurrentDateTime(),
					uinObject.getUin(), uinObject.getUinHash(), uinObject.getUinData(), uinObject.getUinDataHash(),
					uinObject.getRegId(), uinObject.getStatusCode(), IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), false, null));
			
			addIdentityHandle(uinObject, inputSelectedHandlesMap);
			
			issueCredential(uinObject.getUin(), uinHashWithSalt, uinObject.getStatusCode(),
					DateUtils.getUTCCurrentDateTime(),uinObject.getRegId());
			anonymousProfileHelper.buildAndsaveProfile(false);
			return uinObject;
		} catch (JSONException | InvalidJsonException | IOException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, UPDATE_IDENTITY, e.getMessage());
			throw new IdRepoAppException(ID_OBJECT_PROCESSING_FAILED, e);
		} catch (IdRepoAppUncheckedException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, UPDATE_IDENTITY,
					"\n" + e.getErrorText());
			throw new IdRepoAppException(e.getErrorCode(), e.getErrorText(), e);
		}
	}

	/**
	 * trim data inside identity
	 * 
	 * @param request
	 * @throws IdRepoAppException
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void updateRequestBodyData(IdRequestDTO request) throws IdRepoAppException {
		if (trimWhitespaces && Objects.nonNull(request.getIdentity())) {
			Map<String, Object> identityData = idRepoServiceHelper.convertToMap(request.getIdentity());
			Map<String, Object> updatedIdentityData = identityData.entrySet().stream().map(attributeData -> {
				if (attributeData.getValue() instanceof String) {
					attributeData.setValue(((String) attributeData.getValue()).trim());
				} else if (attributeData.getValue() instanceof List) {
					List<Object> updatedListData = ((List<Object>) attributeData.getValue()).stream().map(obj -> {
						if (obj instanceof Map) {
							String trimValue = ((String) ((Map) obj).get(VALUE)).trim();
							((Map) obj).put(VALUE, trimValue);
						} else if (obj instanceof String) {
							obj = ((String) obj).trim();
						}
						return obj;
					}).collect(Collectors.toList());
					attributeData.setValue(updatedListData);
				} else if (attributeData.getValue() instanceof Map) {
					if (((Map) attributeData.getValue()).containsKey(VALUE)) {
						String trimValue = ((String) ((Map) attributeData.getValue()).get(VALUE)).trim();
						((Map) attributeData.getValue()).put(VALUE, trimValue);
					}
				}
				return attributeData;
			}).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			request.setIdentity(updatedIdentityData);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected void updateVerifiedAttributes(IdRequestDTO<T> requestDTO, DocumentContext inputData, DocumentContext dbData) throws IdRepoAppException {
		List dbVerifiedAttributes = (List) dbData.read(DOT + VERIFIED_ATTRIBUTES);
		dbVerifiedAttributes.remove(null);
		boolean isV2Version = requestDTO.getVerifiedAttributes() instanceof Map ? true : false;
		if (dbVerifiedAttributes.isEmpty()) {
			dbVerifiedAttributes.add(isV2Version ? new HashMap<>() : new ArrayList<>());
		}
		if (isV2Version) {
			Map dbVerifiedAttributeMap = (Map) dbVerifiedAttributes.get(0);
			Map<String, Object> identityMap = idRepoServiceHelper.convertToMap(requestDTO.getIdentity());
			dbVerifiedAttributeMap.keySet().removeIf(identityMap::containsKey);
			if (Objects.nonNull(requestDTO.getVerifiedAttributes())
					&& !((Map) requestDTO.getVerifiedAttributes()).isEmpty()) {
				dbVerifiedAttributeMap.putAll((Map) requestDTO.getVerifiedAttributes());
			}
			inputData.put("$", VERIFIED_ATTRIBUTES, dbVerifiedAttributeMap);
			dbData.put("$", VERIFIED_ATTRIBUTES, dbVerifiedAttributeMap);
		} else {
			List verifiedAttributeList = (List) dbVerifiedAttributes.get(0);
			if (Objects.nonNull(requestDTO.getVerifiedAttributes())) {
				verifiedAttributeList.addAll((List<String>)requestDTO.getVerifiedAttributes());
			}
			HashSet<String> verifiedAttributesSet = new HashSet<>(verifiedAttributeList);
			inputData.put("$", VERIFIED_ATTRIBUTES, verifiedAttributesSet);
			dbData.put("$", VERIFIED_ATTRIBUTES, verifiedAttributesSet);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void replaceConfiguredFieldsOnUpdate(DocumentContext inputData, DocumentContext dbData) {
		for(String fieldId : fieldsToReplaceOnUpdate) {
			List readValue = (List) inputData.read(DOT + fieldId);
			if (!readValue.isEmpty()) {
				Object value = readValue.get(0);
				if (Objects.nonNull(value)) {
					dbData.put("$", fieldId, value);
				}
			}
		}
	}

	/**
	 * Update identity.
	 *
	 * @param inputData        the input data
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 * @throws JSONException      the JSON exception
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected void updateJsonObject(String uinHash, DocumentContext inputData, DocumentContext dbData,
			JSONCompareResult comparisonResult, boolean canPersistUpdateCount) throws JSONException, IOException, IdRepoAppException {
		Entry<String, Map<String, Integer>> updateCountTracker = getUpdateCountTracker(uinHash, dbData);
		Map<String, Integer> updateCountTrackerMap = updateCountTracker.getValue();
		Set<String> attribute = new HashSet<>();

		if (comparisonResult.isMissingOnField()) {
			updateMissingFields(dbData, comparisonResult, attribute);
		}

		comparisonResult = JSONCompare.compareJSON(inputData.jsonString(), dbData.jsonString(), JSONCompareMode.LENIENT);
		if (comparisonResult.isFailureOnField()) {
			updateFailingFields(inputData, dbData, comparisonResult, attribute);
		}

		comparisonResult = JSONCompare.compareJSON(inputData.jsonString(), dbData.jsonString(), JSONCompareMode.LENIENT);
		if (!comparisonResult.getMessage().isEmpty()) {
			updateMissingValues(inputData, dbData, comparisonResult, attribute);
		}
		if(canPersistUpdateCount) {
			updateCount(updateCountTrackerMap, attribute);
		}
		comparisonResult = JSONCompare.compareJSON(inputData.jsonString(), dbData.jsonString(), JSONCompareMode.LENIENT);
		if (comparisonResult.failed()) {
			// Code should never reach here
			updateJsonObject(uinHash, inputData, dbData, comparisonResult, true);
		}
		identityUpdateTracker.save(new IdentityUpdateTracker(updateCountTracker.getKey(), CryptoUtil
				.encodeToURLSafeBase64(mapper.writeValueAsString(updateCountTrackerMap).getBytes()).getBytes()));
	}

	private void updateCount(Map<String, Integer> updateCountTrackerMap, Set<String> attributeSet) throws IdRepoAppException {
		mosipLogger.debug("Entering updateCount");
		List<String> attributesHavingLimitExceeded = new ArrayList<>();
		attributeSet.forEach( attribute -> {
			mosipLogger.debug("Processing attribute: {}", attribute);
					if (IdentityUpdateTrackerPolicyProvider.getUpdateCountLimitMap().containsKey(attribute)) {
						Integer currentUpdateCount = updateCountTrackerMap.get(attribute);
						mosipLogger.debug("Current Update Count for {}: {}", attribute, currentUpdateCount);
						if (currentUpdateCount != null) {
							int maxUpdateCountLimit = IdentityUpdateTrackerPolicyProvider.getMaxUpdateCountLimit(attribute);
							mosipLogger.debug("Max Update Count Limit for {}: {}", attribute, maxUpdateCountLimit);
							if (maxUpdateCountLimit - currentUpdateCount <= 0) {
								attributesHavingLimitExceeded.add(attribute);
								mosipLogger.debug("Limit exceeded for {}: {}", attribute, currentUpdateCount);
							}
						}
						updateCountTrackerMap.compute(attribute,
								(k, v) -> (Objects.nonNull(v) ? v + 1 : 1) < IdentityUpdateTrackerPolicyProvider.getMaxUpdateCountLimit(k)
										? (Objects.nonNull(v) ? v + 1 : 1)
										: IdentityUpdateTrackerPolicyProvider.getMaxUpdateCountLimit(k));
						mosipLogger.debug("Updated count for {}: {}", attribute, updateCountTrackerMap.get(attribute));
					}
		}
		);
		if (!attributesHavingLimitExceeded.isEmpty()) {
			String exceededAttributes = String.join(COMMA, attributesHavingLimitExceeded);
			mosipLogger.debug("Limit exceeded for attributes: {}", exceededAttributes);
			throw new IdRepoAppException(UPDATE_COUNT_LIMIT_EXCEEDED.getErrorCode(),
					String.format(UPDATE_COUNT_LIMIT_EXCEEDED.getErrorMessage(), exceededAttributes));
		}
		mosipLogger.debug("Exiting updateCount");
	}

	private Entry<String, Map<String, Integer>> getUpdateCountTracker(String uinHash, DocumentContext dbData)
			throws IOException, JsonParseException, JsonMappingException {
		Optional<IdentityUpdateTracker> updateTrackerOptional = identityUpdateTracker.findById(uinHash);
		Map<String, Integer> updateCountTrackerMap = new HashMap<>();
		if (updateTrackerOptional.isPresent()) {
			updateCountTrackerMap = new HashMap<>(mapper.readValue(
					CryptoUtil.decodeURLSafeBase64(new String(updateTrackerOptional.get().getIdentityUpdateCount())),
					new TypeReference<Map<String, Integer>>() {
					}));
		}
		return Map.entry(uinHash, updateCountTrackerMap);
	}
	
	/**
	 * Update missing fields.
	 *
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 * @param attribute
	 * @throws IdRepoAppException the id repo app exception
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void updateMissingFields(DocumentContext dbData, JSONCompareResult comparisonResult,
									 Set<String> attribute) {
		for (FieldComparisonFailure failure : comparisonResult.getFieldMissing()) {
			if (StringUtils.contains(failure.getField(), OPEN_SQUARE_BRACE)) {
				String path = StringUtils.substringBefore(failure.getField(), OPEN_SQUARE_BRACE);
				String key = StringUtils.substringAfterLast(path, DOT);
				path = StringUtils.substringBeforeLast(path, DOT);

				if (StringUtils.isEmpty(key)) {
					key = path;
					path = ROOT;
				}
				attribute.add(key);
				List value = dbData.read(path + DOT + key, List.class);
				value.addAll(Collections
						.singletonList(convertToObject(failure.getExpected().toString().getBytes(), Map.class)));

				dbData.put(path, key, value);
			} else {
				String path = StringUtils.substringBeforeLast(failure.getField(), DOT);
				if (StringUtils.isEmpty(path)) {
					path = ROOT;
				}
				String key = StringUtils.substringAfterLast(failure.getField(), DOT);
				attribute.add(key);
				dbData.put(path, (String) failure.getExpected(), key);
			}
		}
	}

	/**
	 * Update failing fields.
	 *
	 * @param inputData        the input data
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 * @return
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void updateFailingFields(DocumentContext inputData, DocumentContext dbData,
									 JSONCompareResult comparisonResult, Set<String> attribute) {
		for (FieldComparisonFailure failure : comparisonResult.getFieldFailures()) {

			String path = StringUtils.substringBeforeLast(failure.getField(), DOT);
			if (StringUtils.contains(path, OPEN_SQUARE_BRACE)) {
				path = RegExUtils.replaceAll(path, "\\[", "\\[\\?\\(\\@\\.");
				path = RegExUtils.replaceAll(path, "=", "=='");
				path = RegExUtils.replaceAll(path, "\\]", "'\\)\\]");
			}

			String key = StringUtils.substringAfterLast(failure.getField(), DOT);
			if (StringUtils.isEmpty(key)) {
				key = failure.getField();
				path = ROOT;
			}
			attribute.add(StringUtils.substringBefore(failure.getField(), OPEN_SQUARE_BRACE));
			if (failure.getExpected() instanceof JSONArray) {
				dbData.put(path, key, convertToObject(failure.getExpected().toString().getBytes(), List.class));
				inputData.put(path, key, convertToObject(failure.getExpected().toString().getBytes(), List.class));
			} else if (failure.getExpected() instanceof JSONObject) {
				Object object = convertToObject(failure.getExpected().toString().getBytes(), ObjectNode.class);
				dbData.put(path, key, object);
				inputData.put(path, key, object);
			} else {
				if (!failure.getExpected().toString().contentEquals("null")) {
					dbData.put(path, key, failure.getExpected());
					inputData.put(path, key, failure.getExpected());
				} else {
					inputData.put(path, key, failure.getActual());
				}
			}
		}
	}

	/**
	 * Update missing values.
	 *
	 * @param inputData        the input data
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private void updateMissingValues(DocumentContext inputData, DocumentContext dbData,
									 JSONCompareResult comparisonResult, Set<String> attribute) {
		String path = StringUtils.substringBefore(comparisonResult.getMessage(), OPEN_SQUARE_BRACE);
		String key = StringUtils.substringAfterLast(path, DOT);
		path = StringUtils.substringBeforeLast(path, DOT);

		if (StringUtils.isEmpty(key)) {
			key = path;
			path = ROOT;
		}
		attribute.add(key);
		JsonPath jsonPath = JsonPath.compile(path + DOT + key);
		List<Map<String, String>> dbDataList = dbData.read(path + DOT + key, List.class);
		List<Map<String, String>> inputDataList = inputData.read(path + DOT + key, List.class);
		inputDataList.stream()
				.filter(map -> map.containsKey(LANGUAGE) && dbDataList.stream().filter(dbMap -> dbMap.containsKey(LANGUAGE))
						.allMatch(dbMap -> !StringUtils.equalsIgnoreCase(dbMap.get(LANGUAGE), map.get(LANGUAGE))))
				.forEach(value -> dbData.add(jsonPath, value));
		dbDataList.stream()
				.filter(map -> map.containsKey(LANGUAGE)
						&& inputDataList.stream().filter(inputDataMap -> inputDataMap.containsKey(LANGUAGE)).allMatch(
						inputDataMap -> !StringUtils.equalsIgnoreCase(inputDataMap.get(LANGUAGE), map.get(LANGUAGE))))
				.forEach(value -> inputData.add(jsonPath, value));
	}

	/**
	 * Update documents.
	 *
	 * @param uinHashwithSalt    the uin hash
	 * @param uinObject  the uin object
	 * @param requestDTO the request DTO
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected void updateDocuments(String uinHashwithSalt, Uin uinObject, RequestDTO requestDTO, boolean isDraft)
			throws IdRepoAppException {
		List<UinDocument> docList = new ArrayList<>();
		List<UinBiometric> bioList = new ArrayList<>();

		if (Objects.nonNull(uinObject.getBiometrics())) {
			updateCbeff(uinObject, requestDTO);
		}

		addDocuments(uinHashwithSalt, convertToBytes(requestDTO.getIdentity()), requestDTO.getDocuments(),
				uinObject.getUinRefId(), docList, bioList, isDraft);

		docList.stream().forEach(doc -> uinObject.getDocuments().stream()
				.filter(docObj -> StringUtils.equals(doc.getDoccatCode(), docObj.getDoccatCode())).forEach(docObj -> {
					docObj.setDocId(doc.getDocId());
					docObj.setDocName(doc.getDocName());
					docObj.setDoctypCode(doc.getDoctypCode());
					docObj.setDocfmtCode(doc.getDocfmtCode());
					docObj.setDocHash(doc.getDocHash());
					docObj.setUpdatedBy(IdRepoSecurityManager.getUser());
					docObj.setUpdatedDateTime(doc.getUpdatedDateTime());
				}));
		docList.stream()
				.filter(doc -> uinObject.getDocuments().stream()
						.allMatch(docObj -> !StringUtils.equals(doc.getDoccatCode(), docObj.getDoccatCode())))
				.forEach(doc -> uinObject.getDocuments().add(doc));
		bioList.stream()
				.forEach(bio -> uinObject.getBiometrics().stream()
						.filter(bioObj -> StringUtils.equals(bio.getBiometricFileType(), bioObj.getBiometricFileType()))
						.forEach(bioObj -> {
							bioObj.setBioFileId(bio.getBioFileId());
							bioObj.setBiometricFileName(bio.getBiometricFileName());
							bioObj.setBiometricFileHash(bio.getBiometricFileHash());
							bioObj.setUpdatedBy(IdRepoSecurityManager.getUser());
							bioObj.setUpdatedDateTime(bio.getUpdatedDateTime());
						}));
		bioList.stream()
				.filter(bio -> uinObject.getBiometrics().stream()
						.allMatch(bioObj -> !StringUtils.equals(bio.getBioFileId(), bioObj.getBioFileId())))
				.forEach(bio -> uinObject.getBiometrics().add(bio));
	}

	/**
	 * Update cbeff.
	 *
	 * @param uinObject  the uin object
	 * @param requestDTO the request DTO
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void updateCbeff(Uin uinObject, RequestDTO requestDTO) {
		ObjectNode identityMap = convertToObject(uinObject.getUinData(), ObjectNode.class);
		IntStream.range(0, uinObject.getBiometrics().size()).forEach(index -> {
			UinBiometric bio = uinObject.getBiometrics().get(index);
			requestDTO.getDocuments().stream()
					.filter(doc -> StringUtils.equals(bio.getBiometricFileType(), doc.getCategory())).forEach(doc -> {
						try {
							String uinHash = uinObject.getUinHash().split("_")[1];
							String bioFileId = bio.getBioFileId();
							byte[] data = objectStoreHelper.getBiometricObject(uinHash, bioFileId);
								if (StringUtils.equalsIgnoreCase(
										identityMap.get(bio.getBiometricFileType()).get(FILE_FORMAT_ATTRIBUTE).asText(), CBEFF_FORMAT)
										&& bioFileId.endsWith(CBEFF_FORMAT)) {
									byte[] decodedBioData = CryptoUtil.decodeURLSafeBase64(doc.getValue());
									anonymousProfileHelper.setOldCbeff(CryptoUtil.encodeToURLSafeBase64(data));
									doc.setValue(CryptoUtil.encodeToURLSafeBase64(this.updateXML(decodedBioData, data)));
								}
						} catch (IdRepoAppUncheckedException e) {
							mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "updateCbeff",
									ExceptionUtils.getStackTrace(e));
							throw new IdRepoAppUncheckedException(e.getErrorCode(), e.getErrorText(), e);
						} catch (Exception e) {
							mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "updateCbeff",
									ExceptionUtils.getStackTrace(e));
							throw new IdRepoAppUncheckedException(INVALID_INPUT_PARAMETER.getErrorCode(),
									String.format(INVALID_INPUT_PARAMETER.getErrorMessage(), "documents/" + index + "/value"));
						}
					});
		});
	}

	private byte[] updateXML(byte[] inputBioData, byte[] existingBioData) throws Exception {
		List<BIR> existingBIRData = cbeffUtil.getBIRDataFromXML(existingBioData);
		List<BIR> inputBIRData = cbeffUtil.getBIRDataFromXML(inputBioData);
		Map<String, BIR> inputBIRDataMap = inputBIRData.stream()
				.collect(Collectors.toMap(bir -> bir.getBdbInfo().getType().stream().map(BiometricType::value)
						.collect(Collectors.joining())
						.concat(bir.getBdbInfo().getSubtype().stream().collect(Collectors.joining())), bir -> bir));
		Map<String, BIR> existingBIRDataMap = existingBIRData.stream()
				.collect(Collectors.toMap(bir -> bir.getBdbInfo().getType().stream().map(BiometricType::value)
						.collect(Collectors.joining())
						.concat(bir.getBdbInfo().getSubtype().stream().collect(Collectors.joining())), bir -> bir));
		inputBIRDataMap.entrySet().forEach(entry -> {
			if (existingBIRDataMap.containsKey(entry.getKey())) {
				existingBIRDataMap.replace(entry.getKey(), entry.getValue());
			} else {
				existingBIRDataMap.put(entry.getKey(), entry.getValue());
			}
		});
		byte[] updatedCbeff = cbeffUtil.createXML(new ArrayList<>(existingBIRDataMap.values()));
		anonymousProfileHelper.setNewCbeff(CryptoUtil.encodeToURLSafeBase64(updatedCbeff));
		return updatedCbeff;
	}

	@Override
	public String getRidByIndividualId(String individualId, IdType idType) throws IdRepoAppException {
		return null;
	}

	/**
	 * This function is used to get the maximum allowed update count of an attribute
	 * for the given individual id
	 *
	 * @param idType        The type of the ID. For example, UIN, RID, VID, etc.
	 * @param attributeList List of attributes for which the update count is to be
	 *                      retrieved.
	 * @return A map of attribute name and the maximum allowed update count for that
	 *         attribute.
	 */
	@Override
	public Map<String, Integer> getRemainingUpdateCountByIndividualId(String uinHash, IdType idType,
			List<String> attributeList) throws IdRepoAppException {
		try {
			Optional<IdentityUpdateTracker> updateTrackerOptional = identityUpdateTracker.findById(uinHash);
			if (updateTrackerOptional.isPresent()) {
				IdentityUpdateTracker trackRecord = updateTrackerOptional.get();
				Map<String, Integer> updateCountMap = mapper.readValue(
						CryptoUtil.decodeURLSafeBase64(new String(trackRecord.getIdentityUpdateCount())),
						new TypeReference<Map<String, Integer>>() {
						});
				return addMissingAttributes(
						updateCountMap.entrySet().stream()
								.filter(entry -> attributeList.isEmpty() || attributeList.contains(entry.getKey()))
								.filter(entry -> IdentityUpdateTrackerPolicyProvider.getUpdateCountLimitMap().containsKey(entry.getKey()))
								.collect(Collectors.toMap(
										Map.Entry::getKey,
										entry -> Math.max(0, IdentityUpdateTrackerPolicyProvider.getMaxUpdateCountLimit(entry.getKey()) - entry.getValue())
								))
				);
			} else {
				return getRemainingUpdateCountFromConfig(attributeList);
			}
		} catch (IOException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL,
					"getMaxAllowedUpdateCountForIndividualId", ExceptionUtils.getStackTrace(e));
			throw new IdRepoAppException(UNKNOWN_ERROR);
		}
	}

	private Map<String, Integer> addMissingAttributes(Map<String, Integer> updateCountTracker) {
		IdentityUpdateTrackerPolicyProvider.getUpdateCountLimitMap().entrySet().stream()
				.filter(entry -> !updateCountTracker.containsKey(entry.getKey()))
				.forEach(entry -> updateCountTracker.put(entry.getKey(), entry.getValue()));
		return updateCountTracker;
	}


	private Map<String, Integer> getRemainingUpdateCountFromConfig(List<String> attributeList) {
		if(attributeList == null || attributeList.isEmpty()){
			return IdentityUpdateTrackerPolicyProvider.getUpdateCountLimitMap();
		} else {
			Map<String, Integer> updateCountMapFromPolicy = IdentityUpdateTrackerPolicyProvider.getUpdateCountLimitMap();
			return attributeList.stream()
					.filter(updateCountMapFromPolicy::containsKey)
					.collect(Collectors.toMap(attribute -> attribute, updateCountMapFromPolicy::get));
		}
	}

	private void issueCredential(String enryptedUin, String uinHash, String uinStatus, LocalDateTime expiryTimestamp, String requestId) {
		List<CredentialRequestStatus> credStatusList = credRequestRepo.findByIndividualIdHash(uinHash);
		if (!credStatusList.isEmpty() && uinStatus.contentEquals(activeStatus)) {
			credStatusList.forEach(credStatus -> {
				credStatus.setStatus(CredentialRequestStatusLifecycle.NEW.toString());
				credStatus.setUpdatedBy(IdRepoSecurityManager.getUser());
				credStatus.setUpdDTimes(DateUtils.getUTCCurrentDateTime());
				credRequestRepo.save(credStatus);
			});
		} else if (!credStatusList.isEmpty() && !uinStatus.contentEquals(activeStatus)) {
			credStatusList.forEach(credStatus -> {
				credStatus.setStatus(CredentialRequestStatusLifecycle.DELETED.toString());
				credStatus.setUpdatedBy(IdRepoSecurityManager.getUser());
				credStatus.setUpdDTimes(DateUtils.getUTCCurrentDateTime());
				credRequestRepo.save(credStatus);
			});
		} else if (credStatusList.isEmpty()) {
			CredentialRequestStatus credStatus = new CredentialRequestStatus();
			credStatus.setIndividualId(enryptedUin);
			credStatus.setIndividualIdHash(uinHash);
			credStatus.setPartnerId(dummyPartner.getDummyOLVPartnerId());
			credStatus.setStatus(uinStatus.contentEquals(activeStatus) ? CredentialRequestStatusLifecycle.NEW.toString()
					: CredentialRequestStatusLifecycle.DELETED.toString());
			credStatus.setIdExpiryTimestamp(uinStatus.contentEquals(activeStatus) ? null : expiryTimestamp);
			credStatus.setCreatedBy(IdRepoSecurityManager.getUser());
			credStatus.setCrDTimes(DateUtils.getUTCCurrentDateTime());
			if(enableConventionBasedId && (requestId != null)) {
				credStatus.setRequestId(requestId);
			}
			credRequestRepo.save(credStatus);
		}
	}

	/**
	 * Convert to object.
	 *
	 * @param identity the identity
	 * @param clazz    the clazz
	 * @return the object
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected <T> T convertToObject(byte[] identity, Class<T> clazz) {
		try {
			return mapper.readValue(identity, clazz);
		} catch (IOException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "convertToObject", e.getMessage());
			throw new IdRepoAppUncheckedException(ID_OBJECT_PROCESSING_FAILED, e);
		}
	}

	/**
	 * Convert to bytes.
	 *
	 * @param identity the identity
	 * @return the byte[]
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected byte[] convertToBytes(Object identity) throws IdRepoAppException {
		try {
			return mapper.writeValueAsBytes(identity);
		} catch (JsonProcessingException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "convertToBytes", e.getMessage());
			throw new IdRepoAppException(ID_OBJECT_PROCESSING_FAILED, e);
		}
	}

	/**
	 * Get handles map and check duplicate handles, if duplicate exists then throw
	 * HANDLE_RECORD_EXISTS error.
	 * 
	 * @param request IdRequestDTO
	 * @param uinHash
	 * @param method
	 * @return handles map
	 * @throws IdRepoAppException
	 */
	private Map<String, List<HandleDto>> checkAndGetHandles(IdRequestDTO request, String uinHash,
			Map<String, List<HandleDto>> existingSelectedHandlesMap, String method) throws IdRepoAppException {
		Map<String, List<HandleDto>> handles = idRepoServiceHelper.getSelectedHandles(request,
				existingSelectedHandlesMap);
		if (handles != null && !handles.isEmpty()) {
			List<String> duplicateHandleFieldIds = handles.keySet().stream().filter(handleName -> {
				List<String> hashes = handles.get(handleName).stream().map(HandleDto::getHandleHash).collect(Collectors.toList());
				if(hashes.isEmpty())
					return false;

				List<String> uinHashFromDB = handleRepo.findUinHashByHandleHashes(hashes);
				if (!uinHashFromDB.isEmpty()) {
					//check if belongs to the same user, if yes then don't throw error
					return (method.equals(UPDATE) && uinHashFromDB.contains(uinHash)) ? false : true;
				}
				return false;
			}).collect(Collectors.toList());

			if (duplicateHandleFieldIds != null && !duplicateHandleFieldIds.isEmpty()) {
				throw new IdRepoAppException(HANDLE_RECORD_EXISTS.getErrorCode(),
						String.format(HANDLE_RECORD_EXISTS.getErrorMessage(), duplicateHandleFieldIds));
			}
		}
		return handles;
	}

	private void addIdentityHandle(Uin uinEntity, Map<String, List<HandleDto>> handles) throws IdRepoAppException {
		if(handles == null)
			return;

		for (Entry<String, List<HandleDto>> entry : handles.entrySet()) {
			for(HandleDto handleDto : entry.getValue()) {
				mosipLogger.info("addIdentityHandle - Key : {}", entry.getKey());
				int saltId = securityManager.getSaltKeyForHashOfId(handleDto.getHandle());
				String encryptSalt = uinEncryptSaltRepo.retrieveSaltById(saltId);
				String handleToEncrypt = saltId + SPLITTER + handleDto.getHandle() + SPLITTER + encryptSalt;

				Optional<Handle> result = handleRepo.findByHandleHash(handleDto.getHandleHash());
				if(result.isPresent()) {
					throw new IdRepoAppException(HANDLE_RECORD_EXISTS.getErrorCode(),
							String.format(HANDLE_RECORD_EXISTS.getErrorMessage(), entry.getKey()));
				}

				Handle handleEntity = new Handle();
				handleEntity.setHandleHash(handleDto.getHandleHash());
				handleEntity.setId(UUIDUtils.getUUID(UUIDUtils.NAMESPACE_OID,
						handleDto.getHandle() + SPLITTER + DateUtils.getUTCCurrentDateTime()).toString());
				handleEntity.setHandle(handleToEncrypt);
				handleEntity.setUinHash(uinEntity.getUinHash());
				handleEntity.setCreatedBy(IdRepoSecurityManager.getUser());
				handleEntity.setCreatedDateTime(DateUtils.getUTCCurrentDateTime());
				handleEntity.setStatus(HandleStatusLifecycle.ACTIVATED.name());
				handleRepo.save(handleEntity);
				mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, ADD_IDENTITY_HANDLE,
						"Record successfully saved in db");
			}
		}
	}

	private Map<String, List<HandleDto>> getNewAndDeleteExistingHandles(IdRequestDTO inputRequestDto, Uin uinObject,
			String method) throws IdRepoAppException {
//		fetch existing handles
		ObjectNode identityObject = convertToObject(uinObject.getUinData(), ObjectNode.class);
		RequestDTO existingIdentityRequestDto = new RequestDTO();
		existingIdentityRequestDto.setIdentity(identityObject);
		Map<String, List<HandleDto>> existingSelectedHandlesMap = idRepoServiceHelper
				.getSelectedHandles(existingIdentityRequestDto, null);
		try {
			mosipLogger.info("existingSelectedHandlesMap : {}", mapper.writeValueAsString(existingSelectedHandlesMap));
			//		new handles
			Map<String, List<HandleDto>> inputSelectedHandlesMap = checkAndGetHandles(inputRequestDto, uinObject.getUinHash(),
					existingSelectedHandlesMap, method);
			//		if 'inputSelectedHandlesMap' comes as empty map then we should revoke all existing handles
			//		by updating the handle status as 'DELETE'.
			mosipLogger.info("inputSelectedHandlesMap : {}", mapper.writeValueAsString(inputSelectedHandlesMap));
			if (existingSelectedHandlesMap == null)
				return inputSelectedHandlesMap;

			List<String> handleHashesToBeDeleted = new ArrayList<>();
			for (Entry<String, List<HandleDto>> existingEntry : existingSelectedHandlesMap.entrySet()) {
				for (HandleDto existingHandleDto : existingEntry.getValue()) {
					//if same handle hash present in "inputSelectedHandlesMap" then
					//remove from "inputSelectedHandlesMap" otherwise update handle status as 'DELETE'.
					if (inputSelectedHandlesMap != null && inputSelectedHandlesMap.containsKey(existingEntry.getKey())) {
						Optional<HandleDto> result = inputSelectedHandlesMap.get(existingEntry.getKey())
								.stream()
								.filter(newDto -> newDto.getHandleHash().equals(existingHandleDto.getHandleHash()))
								.findFirst();

						//if the existing handle hash is not present in the new map, then it entry should be "DELETED"
						if (result.isEmpty()) {
							handleHashesToBeDeleted.add(existingHandleDto.getHandleHash());
						} else {
							//handle already exists with the same hash
							inputSelectedHandlesMap.get(existingEntry.getKey()).remove(result.get());
						}

					} else {
						handleHashesToBeDeleted.add(existingHandleDto.getHandleHash());
					}
				}
			}
			mosipLogger.info("handleHashesToBeDeleted : {}", mapper.writeValueAsString(handleHashesToBeDeleted));
			for (String handleHash : handleHashesToBeDeleted) {
				//Update the handle status as 'DELETE' in the "mosip_idrepo.handle" table
				//and will delete the record after getting an acknowledgement from IDA.
				handleRepo.updateStatusByHandleHash(handleHash, DELETE.name());
				mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "getNewAndDeleteExistingHandles", "Record successfully updated in db");
			}
			return inputSelectedHandlesMap;
		} catch (Exception e) {
			mosipLogger.error("Parsing error in getNewAndDeleteExistingHandles:", e);
		}
		return null;
	}
}

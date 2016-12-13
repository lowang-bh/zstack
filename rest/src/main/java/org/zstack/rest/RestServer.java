package org.zstack.rest;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reflections.Reflections;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UriComponentsBuilder;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusEventListener;
import org.zstack.header.Component;
import org.zstack.header.apimediator.ApiMediatorConstant;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.identity.SessionInventory;
import org.zstack.header.identity.SuppressCredentialCheck;
import org.zstack.header.message.*;
import org.zstack.header.query.APIQueryMessage;
import org.zstack.header.query.QueryCondition;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.rest.RestRequest;
import org.zstack.header.rest.RestResponse;
import org.zstack.rest.sdk.JavaSdkTemplate;
import org.zstack.rest.sdk.SdkFile;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.GroovyUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.path.PathUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.util.*;

/**
 * Created by xing5 on 2016/12/7.
 */
public class RestServer implements Component, CloudBusEventListener {
    private static final CLogger logger = Utils.getLogger(RestServer.class);
    private static final Logger requestLogger = LogManager.getLogger("api.request");
    private static ThreadLocal<RequestInfo> requestInfo = new ThreadLocal<>();

    @Autowired
    private CloudBus bus;
    @Autowired
    private AsyncRestApiStore asyncStore;
    @Autowired
    private RESTFacade restf;

    private static class RequestInfo {
        HttpSession session;
        String remoteHost;
        String requestUrl;
        HttpHeaders headers = new HttpHeaders();

        public RequestInfo(HttpServletRequest req) {
            session = req.getSession();
            remoteHost = req.getRemoteHost();

            for (Enumeration e = req.getHeaderNames(); e.hasMoreElements() ;) {
                String name = e.nextElement().toString();
                headers.add(name, req.getHeader(name));
            }

            try {
                requestUrl = URLDecoder.decode(req.getRequestURI(), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new CloudRuntimeException(e);
            }
        }
    }

    private static final String ASYNC_JOB_PATH_PATTERN = String.format("%s/%s/{uuid}", RestConstants.API_VERSION, RestConstants.ASYNC_JOB_PATH);

    public static void generateJavaSdk() {
        String path = PathUtil.join(System.getProperty("user.home"), "zstack-sdk/java");
        File folder = new File(path);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        try {
            Class clz = GroovyUtils.getClass("scripts/SdkApiTemplate.groovy", RestServer.class.getClassLoader());
            Set<Class<?>> apiClasses = Platform.getReflections().getTypesAnnotatedWith(RestRequest.class);

            List<SdkFile> allFiles = new ArrayList<>();
            for (Class apiClz : apiClasses) {
                JavaSdkTemplate tmp = (JavaSdkTemplate) clz.getConstructor(Class.class).newInstance(apiClz);
                allFiles.addAll(tmp.generate());
            }

            JavaSdkTemplate tmp = GroovyUtils.loadClass("scripts/SdkDataStructureGenerator.groovy", RestServer.class.getClassLoader());
            allFiles.addAll(tmp.generate());

            for (SdkFile f : allFiles) {
                //logger.debug(String.format("\n%s", f.getContent()));
                String fpath = PathUtil.join(path, f.getFileName());
                FileUtils.writeStringToFile(new File(fpath), f.getContent());
            }
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public boolean handleEvent(Event e) {
        if (e instanceof APIEvent) {
            asyncStore.complete((APIEvent) e);
        }

        return false;
    }

    class Api {
        Class apiClass;
        Class apiResponseClass;
        RestRequest requestAnnotation;
        RestResponse responseAnnotation;
        Map<String, String> requestMappingFields;
        String path;
        List<String> optionalPaths = new ArrayList<>();
        String actionName;

        Api(Class clz, RestRequest at) {
            apiClass = clz;
            requestAnnotation = at;
            apiResponseClass = at.responseClass();
            path = String.format("%s%s", RestConstants.API_VERSION, at.path());

            if (at.mappingFields().length > 0) {
                requestMappingFields = new HashMap<>();

                for (String mf : at.mappingFields()) {
                    String[] kv = mf.split("=");
                    if (kv.length != 2) {
                        throw new CloudRuntimeException(String.format("bad requestMappingField[%s] of %s", mf, apiClass));
                    }

                    requestMappingFields.put(kv[0].trim(), kv[1].trim());
                }
            }
            responseAnnotation = (RestResponse) apiResponseClass.getAnnotation(RestResponse.class);
            DebugUtils.Assert(responseAnnotation != null, String.format("%s must be annotated with @RestResponse", apiResponseClass));
            Collections.addAll(optionalPaths, at.optionalPaths());

            if (at.isAction()) {
                actionName = StringUtils.removeStart(apiClass.getSimpleName(), "API");
                actionName = StringUtils.removeEnd(actionName, "Msg");
                actionName = StringUtils.uncapitalize(actionName);
            }

            if (!at.isAction() && requestAnnotation.parameterName().isEmpty()) {
                throw new CloudRuntimeException(String.format("Invalid @RestRequest of %s, either isAction must be set to true or" +
                        " parameterName is set to a non-empty string", apiClass.getName()));
            }
        }

        String getMappingField(String key) {
            if (requestMappingFields == null) {
                return null;
            }

            return requestMappingFields.get(key);
        }
    }

    class RestException extends Exception {
        private int statusCode;
        private String error;

        public RestException(int statusCode, String error) {
            this.statusCode = statusCode;
            this.error = error;
        }
    }

    class RestResponseWrapper {
        RestResponse annotation;
        Map<String, String> responseMappingFields = new HashMap<>();
        Class apiResponseClass;

        public RestResponseWrapper(RestResponse annotation, Class apiResponseClass) {
            this.annotation = annotation;
            this.apiResponseClass = apiResponseClass;

            if (annotation.fieldsTo().length > 0) {
                responseMappingFields = new HashMap<>();

                for (String mf : annotation.fieldsTo()) {
                    String[] kv = mf.split("=");
                    if (kv.length != 2) {
                        throw new CloudRuntimeException(String.format("bad mappingFields[%s] of %s", mf, apiResponseClass));
                    }

                    responseMappingFields.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
    }

    void init() throws IllegalAccessException, InstantiationException {
        bus.subscribeEvent(this, new APIEvent());
    }

    private AntPathMatcher matcher = new AntPathMatcher();

    private Map<String, Object> apis = new HashMap<>();
    private Map<Class, RestResponseWrapper> responseAnnotationByClass = new HashMap<>();

    private HttpEntity<String> toHttpEntity(HttpServletRequest req) {
        try {
            String body = IOUtils.toString(req.getReader());
            req.getReader().close();

            HttpHeaders header = new HttpHeaders();
            for (Enumeration e = req.getHeaderNames(); e.hasMoreElements() ;) {
                String name = e.nextElement().toString();
                header.add(name, req.getHeader(name));
            }

            return new HttpEntity<>(body, header);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new CloudRuntimeException(e);
        }
    }

    private void sendResponse(int statusCode, String body, HttpServletResponse rsp) throws IOException {
        if (requestLogger.isTraceEnabled()) {
            RequestInfo info = requestInfo.get();

            StringBuilder sb = new StringBuilder(String.format("[ID: %s] Response to %s (%s),", info.session.getId(),
                    info.remoteHost, info.requestUrl));
            sb.append(String.format(" Status Code: %s,", statusCode));
            sb.append(String.format(" Body: %s", body.isEmpty() ? null : body));

            requestLogger.trace(sb.toString());
        }

        rsp.setStatus(statusCode);
        rsp.getWriter().write(body == null ? "" : body);
    }

    private String getDecodedUrl(HttpServletRequest req) {
        try {
            return URLDecoder.decode(req.getRequestURI(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException(e);
        }
    }

    void handle(HttpServletRequest req, HttpServletResponse rsp) throws IOException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        requestInfo.set(new RequestInfo(req));

        String path = getDecodedUrl(req);
        HttpEntity<String> entity = toHttpEntity(req);

        if (requestLogger.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder(String.format("[ID: %s, Method: %s] Request from %s (to %s), ",
                    req.getSession().getId(), req.getMethod(),
                    req.getRemoteHost(), URLDecoder.decode(req.getRequestURI(), "UTF-8")));
            sb.append(String.format(" Headers: %s,", JSONObjectUtil.toJsonString(entity.getHeaders())));
            if (req.getQueryString() != null && !req.getQueryString().isEmpty()) {
                sb.append(String.format(" Query: %s,", URLDecoder.decode(req.getQueryString(), "UTF-8")));
            }
            sb.append(String.format(" Body: %s", entity.getBody().isEmpty() ? null : entity.getBody()));

            requestLogger.trace(sb.toString());
        }

        if (matcher.match(ASYNC_JOB_PATH_PATTERN, path)) {
            handleJobQuery(req, rsp);
            return;
        }

        Object api = apis.get(path);
        if (api == null) {
            for (String p : apis.keySet()) {
                if (matcher.match(p, path)) {
                    api = apis.get(p);
                    break;
                }
            }
        }

        if (api == null) {
            sendResponse(HttpStatus.NOT_FOUND.value(), String.format("no api mapping to %s", path), rsp);
            return;
        }

        try {
            if (api instanceof Api) {
                handleUniqueApi((Api) api, entity, req, rsp);
            } else {
                handleNonUniqueApi((Collection)api, entity, req, rsp);
            }
        } catch (RestException e) {
            sendResponse(e.statusCode, e.error, rsp);
        } catch (Throwable e) {
            logger.warn(String.format("failed to handle API to %s", path), e);
            sendResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage(), rsp);
        }
    }

    private void handleJobQuery(HttpServletRequest req, HttpServletResponse rsp) throws IOException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        if (!req.getMethod().equals(HttpMethod.GET.name())) {
            sendResponse(HttpStatus.METHOD_NOT_ALLOWED.value(), "only GET method is allowed for querying job status", rsp);
            return;
        }

        Map<String, String> vars = matcher.extractUriTemplateVariables(ASYNC_JOB_PATH_PATTERN, getDecodedUrl(req));
        String uuid = vars.get("uuid");
        AsyncRestQueryResult ret = asyncStore.query(uuid);

        if (ret.getState() == AsyncRestState.expired) {
            sendResponse(HttpStatus.NOT_FOUND.value(), "the job has been expired", rsp);
            return;
        }

        ApiResponse response = new ApiResponse();

        if (ret.getState() == AsyncRestState.processing) {
            sendResponse(HttpStatus.ACCEPTED.value(), response, rsp);
            return;
        }

        // task is done
        APIEvent evt = ret.getResult();
        if (evt.isSuccess()) {
            writeResponse(response, responseAnnotationByClass.get(evt.getClass()), ret.getResult());
            sendResponse(HttpStatus.OK.value(), response, rsp);
        } else {
            response.setError(evt.getErrorCode());
            sendResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), response, rsp);
        }
    }

    private void sendResponse(int statusCode, ApiResponse response, HttpServletResponse rsp) throws IOException {
        sendResponse(statusCode, response.isEmpty() ? "" : JSONObjectUtil.toJsonString(response), rsp);
    }

    private void handleNonUniqueApi(Collection<Api> apis, HttpEntity<String> entity, HttpServletRequest req, HttpServletResponse rsp) throws RestException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException, IOException {
        Map m = JSONObjectUtil.toObject(entity.getBody(), LinkedHashMap.class);
        Api api;

        String parameterName = null;
        if ("POST".equals(req.getMethod())) {
            // create API
            Optional<Api> o = apis.stream().filter(a -> a.requestAnnotation.method().name().equals("POST")).findAny();
            if (!o.isPresent()) {
                throw new RestException(HttpStatus.INTERNAL_SERVER_ERROR.value(), String.format("No creational API found" +
                        " for the path[%s]", req.getRequestURI()));
            }

            api = o.get();
        } else if ("PUT".equals(req.getMethod())) {
            // action API
            Optional<Api> o = apis.stream().filter(a -> m.containsKey(a.actionName)).findAny();

            if (!o.isPresent()) {
                throw new RestException(HttpStatus.BAD_REQUEST.value(), String.format("the body doesn't contain action mapping" +
                        " to the URL[%s]", getDecodedUrl(req)));
            }

            api = o.get();
            parameterName = api.actionName;
        } else if ("GET".equals(req.getMethod())) {
            // query API
            Optional<Api> o = apis.stream().filter(a -> a.requestAnnotation.method().name().equals("GET")).findAny();
            if (!o.isPresent()) {
                throw new RestException(HttpStatus.INTERNAL_SERVER_ERROR.value(), String.format("No query API found" +
                        " for the path[%s]", req.getRequestURI()));
            }

            api = o.get();
        } else if ("DELETE".equals(req.getMethod())) {
            // DELETE API
            Optional<Api> o = apis.stream().filter(a -> a.requestAnnotation.method().name().equals("DELETE")).findAny();
            if (!o.isPresent()) {
                throw new RestException(HttpStatus.INTERNAL_SERVER_ERROR.value(), String.format("No delete API found" +
                        " for the path[%s]", req.getRequestURI()));
            }

            api = o.get();
        } else {
            throw new RestException(HttpStatus.METHOD_NOT_ALLOWED.value(), String.format("The method[%s] is not allowed for" +
                    " the path[%s]", req.getMethod(), req.getRequestURI()));
        }

        parameterName = parameterName == null ? api.requestAnnotation.parameterName() : parameterName;
        handleApi(api, m, parameterName, entity, req, rsp);
    }

    private void handleApi(Api api, Map body, String parameterName, HttpEntity<String> entity, HttpServletRequest req, HttpServletResponse rsp) throws RestException, IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException, IOException {
        String sessionId = null;
        if (!api.apiClass.isAnnotationPresent(SuppressCredentialCheck.class)) {
            String auth = entity.getHeaders().getFirst("Authorization");
            if (auth == null) {
                throw new RestException(HttpStatus.BAD_REQUEST.value(), "missing header 'Authorization'");
            }

            auth = auth.trim();
            if (!auth.startsWith("OAuth")) {
                throw new RestException(HttpStatus.BAD_REQUEST.value(), "Authorization type must be 'OAuth'");
            }

            sessionId = auth.replaceFirst("OAuth", "").trim();
        }

        if (APIQueryMessage.class.isAssignableFrom(api.apiClass)) {
            handleQueryApi(api, sessionId, req, rsp);
            return;
        }

        Map<String, String> vars = matcher.extractUriTemplateVariables(api.path, getDecodedUrl(req));
        Object parameter = body.get(parameterName);

        APIMessage msg;
        if (parameter == null) {
            msg = (APIMessage) api.apiClass.newInstance();
        } else {
            msg = JSONObjectUtil.rehashObject(parameter, (Class<APIMessage>) api.apiClass);
        }

        if (sessionId != null) {
            SessionInventory session = new SessionInventory();
            session.setUuid(sessionId);
            msg.setSession(session);
        }

        Object systemTags = body.get("systemTags");
        if (systemTags != null) {
            msg.setSystemTags((List<String>) systemTags);
        }

        Object userTags = body.get("userTags");
        if (userTags != null) {
            msg.setUserTags((List<String>) userTags);
        }

        for (Map.Entry<String, String> e : vars.entrySet()) {
            // set fields parsed from the URL
            String key = e.getKey();
            String mappingKey = api.getMappingField(key);
            PropertyUtils.setProperty(msg, mappingKey == null ? key : mappingKey, e.getValue());
        }

        msg.setServiceId(ApiMediatorConstant.SERVICE_ID);
        sendMessage(msg, api, rsp);
    }

    static final String[] QUERY_OP = {
            "=", "!=", ">", "<", ">=", "<=", "?=", "!?=", "~=", "!~="
    };

    private void handleQueryApi(Api api, String sessionId, HttpServletRequest req, HttpServletResponse rsp) throws IllegalAccessException, InstantiationException, RestException, IOException, NoSuchMethodException, InvocationTargetException {
        Map<String, String[]> vars = req.getParameterMap();
        APIQueryMessage msg = (APIQueryMessage) api.apiClass.newInstance();

        SessionInventory session = new SessionInventory();
        session.setUuid(sessionId);
        msg.setSession(session);
        msg.setServiceId(ApiMediatorConstant.SERVICE_ID);

        Map<String, String> urlvars = matcher.extractUriTemplateVariables(api.path, getDecodedUrl(req));
        String uuid = urlvars.get("uuid");
        if (uuid != null) {
            // this is a GET /xxxx/uuid
            // return the resource directly
            QueryCondition qc = new QueryCondition();
            qc.setName("uuid");
            qc.setOp("=");
            qc.setValue(uuid);
            msg.getConditions().add(qc);

            sendMessage(msg, api, rsp);
            return;
        }

        // a query with conditions
        for (Map.Entry<String, String[]> e : vars.entrySet()) {
            String varname = e.getKey().trim();
            String varvalue = e.getValue()[0].trim();

            if ("limit".equals(varname)) {
                try {
                    msg.setLimit(Integer.valueOf(varvalue));
                } catch (NumberFormatException ex) {
                    throw new RestException(HttpStatus.BAD_REQUEST.value(), "Invalid query parameter. 'limit' must be an interger");
                }
            } else if ("start".equals(varname)) {
                try {
                    msg.setStart(Integer.valueOf(varvalue));
                } catch (NumberFormatException ex) {
                    throw new RestException(HttpStatus.BAD_REQUEST.value(), "Invalid query parameter. 'start' must be an interger");
                }
            } else if ("count".equals(varname)) {
                msg.setCount(Boolean.valueOf(varvalue));
            } else if ("groupBy".equals(varname)) {
                msg.setGroupBy(varvalue);
            } else if ("replyWithCount".equals(varname)) {
                msg.setReplyWithCount(Boolean.valueOf(varvalue));
            } else if ("sort".equals(varname)) {
                if (varvalue.startsWith("+")) {
                    msg.setSortDirection("asc");
                    varvalue = StringUtils.stripStart(varvalue, "+");
                } else if (varvalue.startsWith("-")) {
                    msg.setSortDirection("desc");
                    varvalue = StringUtils.stripStart(varvalue, "-");
                } else {
                    msg.setSortDirection("asc");
                }

                msg.setSortBy(varvalue);
            } else if ("q".startsWith(varname)) {
                String[] conds = varvalue.split("&");

                for (String cond : conds) {
                    String OP = null;
                    for (String op : QUERY_OP) {
                        if (cond.contains(op)) {
                            OP = op;
                            break;
                        }
                    }

                    if (OP == null) {
                        throw new RestException(HttpStatus.BAD_REQUEST.value(), String.format("Invalid query parameter." +
                                " The '%s' in the parameter[q] doesn't contain any query operator. Valid query operators are" +
                                " %s", QUERY_OP));
                    }

                    String[] ks = cond.split(OP, 2);
                    if (ks.length != 2) {
                        throw new RestException(HttpStatus.BAD_REQUEST.value(), String.format("Invalid query parameter." +
                                " The '%s' in parameter[q] is not a key-value pair split by %s", cond, OP));
                    }

                    String cname = ks[0].trim();
                    String cvalue = ks[1]; // don't trim the value, a space is valid in some conditions
                    QueryCondition qc = new QueryCondition();
                    qc.setName(cname);
                    qc.setOp(OP);
                    qc.setValue(cvalue);
                    msg.getConditions().add(qc);
                }
            } else if ("fields".equals(varname)) {
                List<String> fs = new ArrayList<>();
                for (String f : varvalue.split(",")) {
                    fs.add(f.trim());
                }

                if (fs.isEmpty()) {
                    throw new RestException(HttpStatus.BAD_REQUEST.value(), String.format("Invalid query parameter. 'fields'" +
                            " contains zero field"));
                }
                msg.setFields(fs);
            }
        }

        if (msg.getConditions() == null) {
            // no condition specified, query all
            msg.setConditions(new ArrayList<>());
        }

        sendMessage(msg, api, rsp);
    }

    private void handleUniqueApi(Api api, HttpEntity<String> entity, HttpServletRequest req, HttpServletResponse rsp) throws RestException, IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException, IOException {
        handleApi(api, JSONObjectUtil.toObject(entity.getBody(), LinkedHashMap.class), api.requestAnnotation.parameterName(), entity, req, rsp);
    }

    private void writeResponse(ApiResponse response, RestResponseWrapper w, Object replyOrEvent) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        if (!w.annotation.allTo().equals("")) {
            response.put(w.annotation.allTo(),
                    PropertyUtils.getProperty(replyOrEvent, w.annotation.allTo()));
        } else {
            for (Map.Entry<String, String> e : w.responseMappingFields.entrySet()) {
                response.put(e.getKey(),
                        PropertyUtils.getProperty(replyOrEvent, e.getValue()));
            }
        }

        if (requestInfo.get().headers.containsKey(RestConstants.HEADER_JSON_SCHEMA)) {
            response.setSchema(new JsonSchemaBuilder(response).build());
        }
    }

    private void sendReplyResponse(MessageReply reply, Api api, HttpServletResponse rsp) throws IOException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        ApiResponse response = new ApiResponse();

        if (!reply.isSuccess()) {
            response.setError(reply.getError());
            sendResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), JSONObjectUtil.toJsonString(response), rsp);
            return;
        }

        // the api succeeded

        writeResponse(response, responseAnnotationByClass.get(api.apiResponseClass), reply);
        sendResponse(HttpStatus.OK.value(), response, rsp);
    }

    private void sendMessage(APIMessage msg, Api api, HttpServletResponse rsp) throws IOException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        if (msg instanceof APISyncCallMessage) {
            MessageReply reply = bus.call(msg);
            sendReplyResponse(reply, api, rsp);
        } else {
            String apiUuid = asyncStore.save(msg);
            UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(restf.getBaseUrl());
            ub.path(RestConstants.API_VERSION);
            ub.path(RestConstants.ASYNC_JOB_PATH);
            ub.path("/" + apiUuid);

            ApiResponse response = new ApiResponse();
            response.setLocation(ub.build().toUriString());

            bus.send(msg);

            sendResponse(HttpStatus.ACCEPTED.value(), response, rsp);
        }
    }

    @Override
    public boolean start() {
        build();
        return true;
    }

    private void build() {
        Reflections reflections = Platform.getReflections();
        Set<Class<?>> classes = reflections.getTypesAnnotatedWith(RestRequest.class);

        for (Class clz : classes) {
            RestRequest at = (RestRequest) clz.getAnnotation(RestRequest.class);
            Api api = new Api(clz, at);

            List<String> paths = new ArrayList<>();
            paths.add(api.path);
            paths.addAll(api.optionalPaths);

            for (String path : paths) {
                if (!apis.containsKey(path)) {
                    apis.put(path, api);
                } else {
                    Object c = apis.get(path);
                    List lst;
                    if (c instanceof Api) {
                        lst = new ArrayList();
                        lst.add(c);
                        apis.put(path, lst);
                    } else {
                        lst = (List) c;
                    }
                    lst.add(api);
                }
            }

            responseAnnotationByClass.put(api.apiResponseClass, new RestResponseWrapper(api.responseAnnotation, api.apiResponseClass));
        }
    }

    @Override
    public boolean stop() {
        return true;
    }
}

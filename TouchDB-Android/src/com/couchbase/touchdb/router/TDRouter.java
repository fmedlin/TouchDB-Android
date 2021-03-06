package com.couchbase.touchdb.router;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

import org.codehaus.jackson.map.ObjectMapper;

import android.util.Log;

import com.couchbase.touchdb.TDBody;
import com.couchbase.touchdb.TDChangesOptions;
import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDDatabase.TDContentOptions;
import com.couchbase.touchdb.TDFilterBlock;
import com.couchbase.touchdb.TDMisc;
import com.couchbase.touchdb.TDQueryOptions;
import com.couchbase.touchdb.TDRevision;
import com.couchbase.touchdb.TDRevisionList;
import com.couchbase.touchdb.TDServer;
import com.couchbase.touchdb.TDStatus;
import com.couchbase.touchdb.TDView;
import com.couchbase.touchdb.TDView.TDViewCollation;
import com.couchbase.touchdb.TDViewMapBlock;
import com.couchbase.touchdb.TDViewReduceBlock;
import com.couchbase.touchdb.TouchDBVersion;
import com.couchbase.touchdb.replicator.TDPuller;
import com.couchbase.touchdb.replicator.TDPusher;
import com.couchbase.touchdb.replicator.TDReplicator;


public class TDRouter implements Observer {
    private TDServer server;
    private TDDatabase db;
    private TDURLConnection connection;
    private Map<String,String> queries;
    private boolean changesIncludesDocs = false;
    private TDRouterCallbackBlock callbackBlock;
    private boolean responseSent = false;
    private boolean waiting = false;
    private TDFilterBlock changesFilter;
    private boolean longpoll = false;

    public static String getVersionString() {
        return TouchDBVersion.TouchDBVersionNumber;
    }

    public TDRouter(TDServer server, TDURLConnection connection) {
        this.server = server;
        this.connection = connection;
    }

    public void setCallbackBlock(TDRouterCallbackBlock callbackBlock) {
        this.callbackBlock = callbackBlock;
    }

    public Map<String,String> getQueries() {
        if(queries == null) {
            String queryString = connection.getURL().getQuery();
            if(queryString != null && queryString.length() > 0) {
                queries = new HashMap<String,String>();
                for (String component : queryString.split("&")) {
                    int location = component.indexOf('=');
                    if(location > 0) {
                        String key = component.substring(0, location);
                        String value = component.substring(location + 1);
                        queries.put(key, value);
                    }
                }

            }
        }
        return queries;
    }

    public String getQuery(String param) {
        Map<String,String> queries = getQueries();
        if(queries != null) {
            String value = queries.get(param);
            if(value != null) {
                return URLDecoder.decode(value);
            }
        }
        return null;
    }

    public boolean getBooleanQuery(String param) {
        String value = getQuery(param);
        return (value != null) && !"false".equals(value) && !"0".equals(value);
    }

    public int getIntQuery(String param, int defaultValue) {
        int result = defaultValue;
        String value = getQuery(param);
        if(value != null) {
            try {
                result = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                //ignore, will return default value
            }
        }

        return result;
    }

    public Object getJSONQuery(String param) {
        String value = getQuery(param);
        if(value == null) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        Object result = null;
        try {
            result = mapper.readValue(value, Object.class);
        } catch (Exception e) {
            Log.w("Unable to parse JSON Query", e);
        }
        return result;
    }

    public boolean cacheWithEtag(String etag) {
        String eTag = String.format("\"%s\"", etag);
        connection.getResHeader().add("Etag", eTag);
        String requestIfNoneMatch = connection.getRequestProperty("If-None-Match");
        return eTag.equals(requestIfNoneMatch);
    }

    public Map<String,Object> getBodyAsDictionary() {
        try {
            byte[] bodyBytes = ((ByteArrayOutputStream)connection.getOutputStream()).toByteArray();
            ObjectMapper mapper = new ObjectMapper();
            Map<String,Object> bodyMap = mapper.readValue(bodyBytes, Map.class);
            return bodyMap;
        } catch (IOException e) {
            return null;
        }
    }

    public byte[] getBody() {
        try {
            byte[] bodyBytes = ((ByteArrayOutputStream)connection.getOutputStream()).toByteArray();
            return bodyBytes;
        } catch (IOException e) {
            return null;
        }
    }

    public EnumSet<TDContentOptions> getContentOptions() {
        EnumSet<TDContentOptions> result = EnumSet.noneOf(TDContentOptions.class);
        if(getBooleanQuery("attachments")) {
            result.add(TDContentOptions.TDIncludeAttachments);
        }
        if(getBooleanQuery("local_seq")) {
            result.add(TDContentOptions.TDIncludeLocalSeq);
        }
        if(getBooleanQuery("conflicts")) {
            result.add(TDContentOptions.TDIncludeConflicts);
        }
        if(getBooleanQuery("revs")) {
            result.add(TDContentOptions.TDIncludeRevs);
        }
        if(getBooleanQuery("revs_info")) {
            result.add(TDContentOptions.TDIncludeRevsInfo);
        }
        return result;
    }

    public boolean getQueryOptions(TDQueryOptions options) {
        // http://wiki.apache.org/couchdb/HTTP_view_API#Querying_Options
        options.setSkip(getIntQuery("skip", options.getSkip()));
        options.setLimit(getIntQuery("limit", options.getLimit()));
        options.setGroupLevel(getIntQuery("group_level", options.getGroupLevel()));
        options.setDescending(getBooleanQuery("descending"));
        options.setIncludeDocs(getBooleanQuery("include_docs"));
        options.setUpdateSeq(getBooleanQuery("update_seq"));
        if(getQuery("inclusive_end") != null) {
            options.setInclusiveEnd(getBooleanQuery("inclusive_end"));
        }
        options.setReduce(getBooleanQuery("reduce"));
        options.setGroup(getBooleanQuery("group"));
        options.setContentOptions(getContentOptions());
        options.setStartKey(getJSONQuery("startkey"));
        options.setEndKey(getJSONQuery("endkey"));
        Object key = getJSONQuery("key");
        if(key != null) {
            List<Object> keys = new ArrayList<Object>();
            keys.add(key);
            options.setKeys(keys);
        }
        return true;
    }

    public String getMultipartRequestType() {
        String accept = connection.getRequestProperty("Accept");
        if(accept.startsWith("multipart/")) {
            return accept;
        }
        return null;
    }

    public TDStatus openDB() {
        if(db == null) {
            return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
        }
        if(!db.exists()) {
            return new TDStatus(TDStatus.NOT_FOUND);
        }
        if(!db.open()) {
            return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
        }
        return new TDStatus(TDStatus.OK);
    }

    public static List<String> splitPath(URL url) {
        String pathString = url.getPath();
        if(pathString.startsWith("/")) {
            pathString = pathString.substring(1);
        }
        List<String> result = new ArrayList<String>();
        //we want empty string to return empty list
        if(pathString.length() == 0) {
            return result;
        }
        for (String component : pathString.split("/")) {
            result.add(URLDecoder.decode(component));
        }
        return result;
    }

    public void sendResponse() {
        if(!responseSent) {
            responseSent = true;
            if(callbackBlock != null) {
                callbackBlock.onResponseReady();
            }
        }
    }

    public void start() {
        // Refer to: http://wiki.apache.org/couchdb/Complete_HTTP_API_Reference

        // We're going to map the request into a method call using reflection based on the method and path.
        // Accumulate the method name into the string 'message':
        String method = connection.getRequestMethod();
        if("HEAD".equals(method)) {
            method = "GET";
        }
        String message = String.format("do_%s", method);

        // First interpret the components of the request:
        List<String> path = splitPath(connection.getURL());
        if(path == null) {
            connection.setResponseCode(TDStatus.BAD_REQUEST);
            return;
        }

        int pathLen = path.size();
        if(pathLen > 0) {
            String dbName = path.get(0);
            if(dbName.startsWith("_")) {
                message += dbName;  // special root path, like /_all_dbs
            } else {
                message += "_Database";
                db = server.getDatabaseNamed(dbName);
                if(db == null) {
                    connection.setResponseCode(TDStatus.BAD_REQUEST);
                    return;
                }
            }
        } else {
            message += "Root";
        }

        String docID = null;
        if(db != null && pathLen > 1) {
            message = message.replaceFirst("_Database", "_Document");
            // Make sure database exists, then interpret doc name:
            TDStatus status = openDB();
            if(!status.isSuccessful()) {
                connection.setResponseCode(status.getCode());
                return;
            }
            String name = path.get(1);
            if(!name.startsWith("_")) {
                // Regular document
                if(!TDDatabase.isValidDocumentId(name)) {
                    connection.setResponseCode(TDStatus.BAD_REQUEST);
                    return;
                }
                docID = name;
            } else if("_design".equals(name) || "_local".equals(name)) {
                // "_design/____" and "_local/____" are document names
                if(pathLen <= 2) {
                    connection.setResponseCode(TDStatus.NOT_FOUND);
                    return;
                }
                docID = name + "/" + path.get(2);
                path.set(1, docID);
                path.remove(2);
                pathLen--;
            } else if(name.startsWith("_design") || name.startsWith("_local")) {
                // This is also a document, just with a URL-encoded "/"
                docID = name;
            } else {
                // Special document name like "_all_docs":
                message += name;
                if(pathLen > 2) {
                    List<String> subList = path.subList(2, pathLen-1);
                    StringBuilder sb = new StringBuilder();
                    Iterator<String> iter = subList.iterator();
                    while(iter.hasNext()) {
                        sb.append(iter.next());
                        if(iter.hasNext()) {
                            sb.append("/");
                        }
                    }
                    docID = sb.toString();
                }
            }
        }

        String attachmentName = null;
        if(docID != null && pathLen > 2) {
            message = message.replaceFirst("_Document", "_Attachment");
            // Interpret attachment name:
            attachmentName = path.get(2);
            if(attachmentName.startsWith("_") && docID.startsWith("_design")) {
                // Design-doc attribute like _info or _view
                message = message.replaceFirst("_Attachment", "_DesignDocument");
                docID = docID.substring(8); // strip the "_design/" prefix
                attachmentName = pathLen > 3 ? path.get(3) : null;
            }
        }

        // Send myself a message based on the components:
        TDStatus status = new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
        try {
            Method m = this.getClass().getMethod(message, TDDatabase.class, String.class, String.class);
            status = (TDStatus)m.invoke(this, db, docID, attachmentName);
        } catch (NoSuchMethodException msme) {
            try {
                Method m = this.getClass().getMethod("do_UNKNOWN", TDDatabase.class, String.class, String.class);
                status = (TDStatus)m.invoke(this, db, docID, attachmentName);
            } catch (Exception e) {
                //default status is internal server error
            }
        } catch (Exception e) {
            //default status is internal server error
            Log.e(TDDatabase.TAG, "Exception in TDRouter", e);
        }

        // Configure response headers:
        if(status.isSuccessful() && connection.getResponseBody() == null && connection.getHeaderField("Content-Type") == null) {
            connection.setResponseBody(new TDBody("{\"ok\":true}".getBytes()));
        }

        if(connection.getResponseBody() != null && connection.getResponseBody() == null && connection.getResponseBody().isValidJSON()) {
            connection.getResHeader().add("Content-Type", "application/json");
        }

        // Check for a mismatch between the Accept request header and the response type:
        String accept = connection.getRequestProperty("Accept");
        if(accept != null && !"*/*".equals(accept)) {
            String responseType = connection.getBaseContentType();
            if(responseType != null && accept.indexOf(responseType) < 0) {
                Log.e(TDDatabase.TAG, String.format("Error 406: Can't satisfy request Accept: %s", accept));
                status = new TDStatus(TDStatus.NOT_ACCEPTABLE);
            }
        }

        connection.getResHeader().add("Server", String.format("TouchDB %s", getVersionString()));

        // If response is ready (nonzero status), tell my client about it:
        if(status.getCode() != 0) {
            connection.setResponseCode(status.getCode());
            sendResponse();
            if(callbackBlock != null && connection.getResponseBody() != null) {
                callbackBlock.onDataAvailable(connection.getResponseBody().getJson());
            }
            if(callbackBlock != null && !waiting) {
                callbackBlock.onFinish();
            }
        }
    }

    public void stop() {
        callbackBlock = null;
        if(db != null) {
            db.deleteObserver(this);
        }
    }

    public TDStatus do_UNKNOWN(TDDatabase db, String docID, String attachmentName) {
        return new TDStatus(TDStatus.BAD_REQUEST);
    }

    /*************************************************************************************************/
    /*** TDRouter+Handlers                                                                         ***/
    /*************************************************************************************************/

    public void setResponseLocation(URL url) {
        String location = url.toExternalForm();
        String query = url.getQuery();
        if(query != null) {
            int startOfQuery = location.indexOf(query);
            if(startOfQuery > 0) {
                location = location.substring(0, startOfQuery);
            }
        }
        connection.getResHeader().add("Location", location);
    }

    /** SERVER REQUESTS: **/

    public TDStatus do_GETRoot(TDDatabase _db, String _docID, String _attachmentName) {
        Map<String,Object> info = new HashMap<String,Object>();
        info.put("TouchDB", "Welcome");
        info.put("couchdb", "Welcome"); // for compatibility
        info.put("version", getVersionString());
        connection.setResponseBody(new TDBody(info));
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_GET_all_dbs(TDDatabase _db, String _docID, String _attachmentName) {
        List<String> dbs = server.allDatabaseNames();
        connection.setResponseBody(new TDBody(dbs));
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_POST_replicate(TDDatabase _db, String _docID, String _attachmentName) {
        // Extract the parameters from the JSON request body:
        // http://wiki.apache.org/couchdb/Replication
        Map<String,Object> body = getBodyAsDictionary();
        if(body == null) {
            return new TDStatus(TDStatus.BAD_REQUEST);
        }
        String source = (String)body.get("source");
        String target = (String)body.get("target");
        Boolean createTargetBoolean = (Boolean)body.get("create_target");
        boolean createTarget = (createTargetBoolean != null && createTargetBoolean.booleanValue());
        Boolean continuousBoolean = (Boolean)body.get("continuous");
        boolean continuous = (continuousBoolean != null && continuousBoolean.booleanValue());
        Boolean cancelBoolean = (Boolean)body.get("cancel");
        boolean cancel = (cancelBoolean != null && cancelBoolean.booleanValue());

        // Map the 'source' and 'target' JSON params to a local database and remote URL:
        if(source == null || target == null) {
            return new TDStatus(TDStatus.BAD_REQUEST);
        }
        boolean push = false;
        TDDatabase db = server.getExistingDatabaseNamed(source);
        String remoteStr = null;
        if(db != null) {
            remoteStr = target;
            push = true;
        } else {
            remoteStr = source;
            if(createTarget && !cancel) {
                db = server.getDatabaseNamed(target);
                if(!db.open()) {
                    return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
                }
            } else {
                db = server.getExistingDatabaseNamed(target);
            }
            if(db == null) {
                return new TDStatus(TDStatus.NOT_FOUND);
            }
        }

        URL remote = null;
        try {
            remote = new URL(remoteStr);
        } catch (MalformedURLException e) {
            return new TDStatus(TDStatus.BAD_REQUEST);
        }
        if(remote == null || !remote.getProtocol().equals("http")) {
            return new TDStatus(TDStatus.BAD_REQUEST);
        }

        if(!cancel) {
            // Start replication:
            TDReplicator repl = db.getReplicator(remote, push, continuous);
            if(repl == null) {
                return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
            }
            if(push) {
                ((TDPusher)repl).setCreateTarget(createTarget);
            } else {
                TDPuller pullRepl = (TDPuller)repl;
                String filterName = (String)body.get("filter");
                if(filterName != null) {
                    pullRepl.setFilterName(filterName);
                    Map<String,Object> filterParams = (Map<String,Object>)body.get("query_params");
                    if(filterParams != null) {
                        pullRepl.setFilterParams(filterParams);
                    }
                }
            }
            repl.start();
            Map<String,Object> result = new HashMap<String,Object>();
            result.put("session_id", repl.getSessionID());
            connection.setResponseBody(new TDBody(result));
        } else {
            // Cancel replication:
            TDReplicator repl = db.getActiveReplicator(remote, push);
            if(repl == null) {
                return new TDStatus(TDStatus.NOT_FOUND);
            }
            repl.stop();
        }
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_GET_uuids(TDDatabase _db, String _docID, String _attachmentName) {
        int count = Math.min(1000, getIntQuery("count", 1));
        List<String> uuids = new ArrayList<String>(count);
        for(int i=0; i<count; i++) {
            uuids.add(TDDatabase.generateDocumentId());
        }
        Map<String,Object> result = new HashMap<String,Object>();
        result.put("uuids", uuids);
        connection.setResponseBody(new TDBody(result));
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_GET_active_tasks(TDDatabase _db, String _docID, String _attachmentName) {
        // http://wiki.apache.org/couchdb/HttpGetActiveTasks
        List<Map<String,Object>> activities = new ArrayList<Map<String,Object>>();
        for (TDDatabase db : server.allOpenDatabases()) {
            for (TDReplicator replicator : db.getActiveReplicators()) {
                String source = replicator.getRemote().toExternalForm();
                String target = db.getName();
                if(replicator.isPush()) {
                    String tmp = source;
                    source = target;
                    target = tmp;
                }
                int processed = replicator.getChangesProcessed();
                int total = replicator.getChangesTotal();
                String status = String.format("Processed %d / %d changes", processed, total);
                int progress = (total > 0) ? Math.round(100 * processed / (float)total) : 0;
                Map<String,Object> activity = new HashMap<String,Object>();
                activity.put("type", "Replication");
                activity.put("task", replicator.getSessionID());
                activity.put("source", source);
                activity.put("target", target);
                activity.put("status", status);
                activity.put("progress", progress);
                activities.add(activity);
            }
        }
        connection.setResponseBody(new TDBody(activities));
        return new TDStatus(TDStatus.OK);
    }

    /** DATABASE REQUESTS: **/

    public TDStatus do_GET_Database(TDDatabase _db, String _docID, String _attachmentName) {
        // http://wiki.apache.org/couchdb/HTTP_database_API#Database_Information
        TDStatus status = openDB();
        if(!status.isSuccessful()) {
            return status;
        }
        int num_docs = db.getDocumentCount();
        long update_seq = db.getLastSequence();
        Map<String, Object> result = new HashMap<String,Object>();
        result.put("db_name", db.getName());
        result.put("db_uuid", db.publicUUID());
        result.put("doc_count", num_docs);
        result.put("update_seq", update_seq);
        result.put("disk_size", db.totalDataSize());
        connection.setResponseBody(new TDBody(result));
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_PUT_Database(TDDatabase _db, String _docID, String _attachmentName) {
        if(db.exists()) {
            return new TDStatus(TDStatus.PRECONDITION_FAILED);
        }
        if(!db.open()) {
            return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
        }
        setResponseLocation(connection.getURL());
        return new TDStatus(TDStatus.CREATED);
    }

    public TDStatus do_DELETE_Database(TDDatabase _db, String _docID, String _attachmentName) {
        if(getQuery("rev") != null) {
            return new TDStatus(TDStatus.BAD_REQUEST);  // CouchDB checks for this; probably meant to be a document deletion
        }
        return server.deleteDatabaseNamed(db.getName()) ? new TDStatus(TDStatus.OK) : new TDStatus(TDStatus.NOT_FOUND);
    }

    public TDStatus do_POST_Database(TDDatabase _db, String _docID, String _attachmentName) {
        TDStatus status = openDB();
        if(!status.isSuccessful()) {
            return status;
        }
        return update(db, null, getBodyAsDictionary(), false);
    }

    public TDStatus do_GET_Document_all_docs(TDDatabase _db, String _docID, String _attachmentName) {
        TDQueryOptions options = new TDQueryOptions();
        if(!getQueryOptions(options)) {
            return new TDStatus(TDStatus.BAD_REQUEST);
        }
        Map<String,Object> result = db.getAllDocs(options);
        if(result == null) {
            return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
        }
        connection.setResponseBody(new TDBody(result));
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_POST_Document_all_docs(TDDatabase _db, String _docID, String _attachmentName) {
        //FIXME implement
        throw new UnsupportedOperationException();
    }

    public TDStatus do_POST_Document_bulk_docs(TDDatabase _db, String _docID, String _attachmentName) {
        //FIXME implement
        throw new UnsupportedOperationException();
    }

    public TDStatus do_POST_Document_revs_diff(TDDatabase _db, String _docID, String _attachmentName) {
        //FIXME implement
        throw new UnsupportedOperationException();
    }

    public TDStatus do_POST_Document_compact(TDDatabase _db, String _docID, String _attachmentName) {
        //FIXME implement
        throw new UnsupportedOperationException();
    }

    public TDStatus do_POST_Document_ensure_full_commit(TDDatabase _db, String _docID, String _attachmentName) {
        return new TDStatus(TDStatus.OK);
    }

    /** CHANGES: **/

    public Map<String,Object> changesDictForRevision(TDRevision rev) {
        Map<String,Object> changesDict = new HashMap<String, Object>();
        changesDict.put("rev", rev.getRevId());

        List<Map<String,Object>> changes = new ArrayList<Map<String,Object>>();
        changes.add(changesDict);

        Map<String,Object> result = new HashMap<String,Object>();
        result.put("seq", rev.getSequence());
        result.put("id", rev.getDocId());
        result.put("changes", changes);
        if(rev.isDeleted()) {
            result.put("deleted", true);
        }
        if(changesIncludesDocs) {
            result.put("doc", rev.getProperties());
        }
        return result;
    }

    public Map<String,Object> responseBodyForChanges(List<TDRevision> changes, long since) {
        List<Map<String,Object>> results = new ArrayList<Map<String,Object>>();
        for (TDRevision rev : changes) {
            Map<String,Object> changeDict = changesDictForRevision(rev);
            results.add(changeDict);
        }
        if(changes.size() > 0) {
            since = changes.get(changes.size() - 1).getSequence();
        }
        Map<String,Object> result = new HashMap<String,Object>();
        result.put("results", results);
        result.put("last_seq", since);
        return result;
    }

    public Map<String, Object> responseBodyForChangesWithConflicts(List<TDRevision> changes, long since) {
        // Assumes the changes are grouped by docID so that conflicts will be adjacent.
        List<Map<String,Object>> entries = new ArrayList<Map<String, Object>>();
        String lastDocID = null;
        Map<String, Object> lastEntry = null;
        for (TDRevision rev : changes) {
            String docID = rev.getDocId();
            if(docID.equals(lastDocID)) {
                Map<String,Object> changesDict = new HashMap<String, Object>();
                changesDict.put("rev", rev.getRevId());
                List<Map<String,Object>> inchanges = (List<Map<String,Object>>)lastEntry.get("changes");
                inchanges.add(changesDict);
            } else {
                lastEntry = changesDictForRevision(rev);
                entries.add(lastEntry);
                lastDocID = docID;
            }
        }
        // After collecting revisions, sort by sequence:
        Collections.sort(entries, new Comparator<Map<String,Object>>() {
           public int compare(Map<String,Object> e1, Map<String,Object> e2) {
               return TDMisc.TDSequenceCompare((Long)e1.get("seq"), (Long)e2.get("seq"));
           }
        });

        Long lastSeq = (Long)entries.get(entries.size() - 1).get("seq");
        if(lastSeq == null) {
            lastSeq = since;
        }

        Map<String,Object> result = new HashMap<String,Object>();
        result.put("results", entries);
        result.put("last_seq", lastSeq);
        return result;
    }

    public void sendContinuousChange(TDRevision rev) {
        Map<String,Object> changeDict = changesDictForRevision(rev);
        ObjectMapper mapper = new ObjectMapper();
        try {
            String jsonString = mapper.writeValueAsString(changeDict);
            if(callbackBlock != null) {
                byte[] json = (jsonString + "\n").getBytes();
                callbackBlock.onDataAvailable(json);
            }
        } catch (Exception e) {
            Log.w("Unable to serialize change to JSON", e);
        }
    }

    @Override
    public void update(Observable observable, Object changeObject) {
        if(observable == db) {
            //make sure we're listening to the right events
            Map<String,Object> changeNotification = (Map<String,Object>)changeObject;

            TDRevision rev = (TDRevision)changeNotification.get("rev");

            if(changesFilter != null && !changesFilter.filter(rev)) {
                return;
            }

            if(longpoll) {
                Log.w(TDDatabase.TAG, "TDRouter: Sending longpoll response");
                sendResponse();
                List<TDRevision> revs = new ArrayList<TDRevision>();
                revs.add(rev);
                Map<String,Object> body = responseBodyForChanges(revs, 0);
                if(callbackBlock != null) {
                    ObjectMapper mapper = new ObjectMapper();
                    byte[] data = null;
                    try {
                        data = mapper.writeValueAsBytes(body);
                    } catch (Exception e) {
                        Log.w(TDDatabase.TAG, "Error serializing JSON", e);
                    }
                    callbackBlock.onDataAvailable(data);
                    callbackBlock.onFinish();
                }
            } else {
                Log.w(TDDatabase.TAG, "TDRouter: Sending continous change chunk");
                sendContinuousChange(rev);
            }

        }

    }

    public TDStatus do_GET_Document_changes(TDDatabase _db, String docID, String _attachmentName) {
        // http://wiki.apache.org/couchdb/HTTP_database_API#Changes
        TDChangesOptions options = new TDChangesOptions();
        changesIncludesDocs = getBooleanQuery("include_docs");
        options.setIncludeDocs(changesIncludesDocs);
        String style = getQuery("style");
        if(style != null && style.equals("all_docs")) {
            options.setIncludeConflicts(true);
        }
        options.setContentOptions(getContentOptions());
        options.setSortBySequence(!options.isIncludeConflicts());
        options.setLimit(getIntQuery("limit", options.getLimit()));

        int since = getIntQuery("since", 0);

        String filterName = getQuery("filter");
        if(filterName != null) {
            changesFilter = db.getFilterNamed(filterName);
            if(changesFilter == null) {
                return new TDStatus(TDStatus.NOT_FOUND);
            }
        }

        TDRevisionList changes = db.changesSince(since, options, changesFilter);

        if(changes == null) {
            return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
        }

        String feed = getQuery("feed");
        longpoll = "longpoll".equals(feed);
        boolean continuous = !longpoll && "continuous".equals(feed);

        if(continuous || (longpoll && changes.size() == 0)) {
            connection.setChunked(true);
            connection.setResponseCode(TDStatus.OK);
            sendResponse();
            if(continuous) {
                for (TDRevision rev : changes) {
                    sendContinuousChange(rev);
                }
            }
            db.addObserver(this);
         // Don't close connection; more data to come
            return new TDStatus(0);
        } else {
            if(options.isIncludeConflicts()) {
                connection.setResponseBody(new TDBody(responseBodyForChangesWithConflicts(changes, since)));
            } else {
                connection.setResponseBody(new TDBody(responseBodyForChanges(changes, since)));
            }
            return new TDStatus(TDStatus.OK);
        }
    }

    /** DOCUMENT REQUESTS: **/

    public String getRevIDFromIfMatchHeader() {
        String ifMatch = connection.getRequestProperty("If-Match");
        if(ifMatch == null) {
            return null;
        }
        // Value of If-Match is an ETag, so have to trim the quotes around it:
        if(ifMatch.length() > 2 && ifMatch.startsWith("\"") && ifMatch.endsWith("\"")) {
            return ifMatch.substring(1,ifMatch.length() - 2);
        } else {
            return null;
        }
    }

    public String setResponseEtag(TDRevision rev) {
        String eTag = String.format("\"%s\"", rev.getRevId());
        connection.getResHeader().add("Etag", eTag);
        return eTag;
    }

    public TDStatus do_GET_Document(TDDatabase _db, String docID, String _attachmentName) {
        // http://wiki.apache.org/couchdb/HTTP_Document_API#GET
        boolean isLocalDoc = docID.startsWith("_local");
        EnumSet<TDContentOptions> options = getContentOptions();
        String openRevsParam = getQuery("open_revs");
        if(openRevsParam == null || isLocalDoc) {
            // Regular GET:
            String revID = getQuery("rev");  // often null
            TDRevision rev = null;
            if(isLocalDoc) {
                rev = db.getLocalDocument(docID, revID);
            } else {
                rev = db.getDocumentWithIDAndRev(docID, revID, options);
            }
            if(rev == null) {
                return new TDStatus(TDStatus.NOT_FOUND);
            }
            if(cacheWithEtag(rev.getRevId())) {
                return new TDStatus(TDStatus.NOT_MODIFIED);  // set ETag and check conditional GET
            }

            connection.setResponseBody(rev.getBody());
        } else {
            List<Map<String,Object>> result = null;
            if(openRevsParam.equals("all")) {
                // Get all conflicting revisions:
                TDRevisionList allRevs = db.getAllRevisionsOfDocumentID(docID, true);
                result = new ArrayList<Map<String,Object>>(allRevs.size());
                for (TDRevision rev : allRevs) {
                    TDStatus status = db.loadRevisionBody(rev, options);
                    if(status.isSuccessful()) {
                        Map<String, Object> dict = new HashMap<String,Object>();
                        dict.put("ok", rev.getProperties());
                        result.add(dict);
                    } else if(status.getCode() != TDStatus.INTERNAL_SERVER_ERROR) {
                        Map<String, Object> dict = new HashMap<String,Object>();
                        dict.put("missing", rev.getRevId());
                        result.add(dict);
                    } else {
                        return status;  // internal error getting revision
                    }
                }
            } else {
                // ?open_revs=[...] returns an array of revisions of the document:
                List<String> openRevs = (List<String>)getJSONQuery("open_revs");
                if(openRevs == null) {
                    return new TDStatus(TDStatus.BAD_REQUEST);
                }
                result = new ArrayList<Map<String,Object>>(openRevs.size());
                for (String revID : openRevs) {
                    TDRevision rev = db.getDocumentWithIDAndRev(docID, revID, options);
                    if(rev != null) {
                        Map<String, Object> dict = new HashMap<String,Object>();
                        dict.put("ok", rev.getProperties());
                        result.add(dict);
                    } else {
                        Map<String, Object> dict = new HashMap<String,Object>();
                        dict.put("missing", revID);
                        result.add(dict);
                    }
                }
            }
            String acceptMultipart  = getMultipartRequestType();
            if(acceptMultipart != null) {
                //FIXME figure out support for multipart
                throw new UnsupportedOperationException();
            } else {
                connection.setResponseBody(new TDBody(result));
            }
        }
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_GET_Attachment(TDDatabase _db, String docID, String _attachmentName) {
        //FIXME implement
        throw new UnsupportedOperationException();
    }

    /**
     * NOTE this departs from the iOS version, returning revision, passing status back by reference
     */
    public TDRevision update(TDDatabase _db, String docID, TDBody body, boolean deleting, boolean allowConflict, TDStatus outStatus) {
        boolean isLocalDoc = docID != null && docID.startsWith(("_local"));
        String prevRevID = null;

        if(!deleting) {
            Boolean deletingBoolean = (Boolean)body.getPropertyForKey("deleted");
            deleting = (deletingBoolean != null && deletingBoolean.booleanValue());
            if(docID == null) {
                if(isLocalDoc) {
                    outStatus.setCode(TDStatus.METHOD_NOT_ALLOWED);
                    return null;
                }
                // POST's doc ID may come from the _id field of the JSON body, else generate a random one.
                docID = (String)body.getPropertyForKey("_id");
                if(docID == null) {
                    if(deleting) {
                        outStatus.setCode(TDStatus.BAD_REQUEST);
                        return null;
                    }
                    docID = TDDatabase.generateDocumentId();
                }
            }
            // PUT's revision ID comes from the JSON body.
            prevRevID = (String)body.getPropertyForKey("_rev");
        } else {
            // DELETE's revision ID comes from the ?rev= query param
            prevRevID = getQuery("rev");
        }

        // A backup source of revision ID is an If-Match header:
        if(prevRevID == null) {
            prevRevID = getRevIDFromIfMatchHeader();
        }

        TDRevision rev = new TDRevision(docID, null, deleting);
        rev.setBody(body);

        TDRevision result = null;
        TDStatus tmpStatus = new TDStatus();
        if(isLocalDoc) {
            result = _db.putLocalRevision(rev, prevRevID, tmpStatus);
        } else {
            result = _db.putRevision(rev, prevRevID, allowConflict, tmpStatus);
        }
        outStatus.setCode(tmpStatus.getCode());
        return result;
    }

    public TDStatus update(TDDatabase _db, String docID, Map<String,Object> bodyDict, boolean deleting) {
        TDBody body = new TDBody(bodyDict);
        TDStatus status = new TDStatus();
        TDRevision rev = update(_db, docID, body, deleting, false, status);

        if(status.isSuccessful()) {
            cacheWithEtag(rev.getRevId());  // set ETag
            if(!deleting) {
                URL url = connection.getURL();
                String urlString = url.toExternalForm();
                if(docID != null) {
                    urlString += "/" + rev.getDocId();
                    try {
                        url = new URL(urlString);
                    } catch (MalformedURLException e) {
                        Log.w("Malformed URL", e);
                    }
                }
                setResponseLocation(url);
            }
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("ok", true);
            result.put("id", rev.getDocId());
            result.put("rev", rev.getRevId());
            connection.setResponseBody(new TDBody(result));
        }
        return status;
    }

    public TDStatus do_PUT_Document(TDDatabase _db, String docID, String _attachmentName) {
        Map<String,Object> bodyDict = getBodyAsDictionary();
        if(bodyDict == null) {
            return new TDStatus(TDStatus.BAD_REQUEST);
        }

        if(getQuery("new_edits") == null || (getQuery("new_edits") != null && (new Boolean(getQuery("new_edits"))))) {
            // Regular PUT
            return update(_db, docID, bodyDict, false);
        } else {
            // PUT with new_edits=false -- forcible insertion of existing revision:
            TDBody body = new TDBody(bodyDict);
            TDRevision rev = new TDRevision(body);
            if(rev.getRevId() == null || rev.getDocId() == null || !rev.getDocId().equals(docID)) {
                return new TDStatus(TDStatus.BAD_REQUEST);
            }
            List<String> history = TDDatabase.parseCouchDBRevisionHistory(body.getProperties());
            return db.forceInsert(rev, history, null);
        }
    }

    public TDStatus do_DELETE_Document(TDDatabase _db, String docID, String _attachmentName) {
        return update(_db, docID, null, true);
    }

    public TDStatus updateAttachment(String attachment, String docID, byte[] body) {
        TDStatus status = new TDStatus();
        String revID = getQuery("rev");
        if(revID == null) {
            revID = getRevIDFromIfMatchHeader();
        }
        TDRevision rev = db.updateAttachment(attachment, body, connection.getRequestProperty("Content-Type"),
                docID, revID, status);
        if(status.isSuccessful()) {
            Map<String, Object> resultDict = new HashMap<String, Object>();
            resultDict.put("ok", true);
            resultDict.put("id", rev.getDocId());
            resultDict.put("rev", rev.getRevId());
            connection.setResponseBody(new TDBody(resultDict));
            cacheWithEtag(rev.getRevId());
            if(body != null) {
                setResponseLocation(connection.getURL());
            }
        }
        return status;
    }

    public TDStatus do_PUT_Attachment(TDDatabase _db, String docID, String _attachmentName) {
        return updateAttachment(_attachmentName, docID, getBody());
    }

    public TDStatus do_DELETE_Attachment(TDDatabase _db, String docID, String _attachmentName) {
        return updateAttachment(_attachmentName, docID, null);
    }

    /** VIEW QUERIES: **/

    public TDView compileView(String viewName, Map<String,Object> viewProps) {
        String language = (String)viewProps.get("language");
        if(language == null) {
            language = "javascript";
        }
        String mapSource = (String)viewProps.get("map");
        if(mapSource == null) {
            return null;
        }
        TDViewMapBlock mapBlock = TDView.getCompiler().compileMapFunction(mapSource, language);
        if(mapBlock == null) {
            Log.w(TDDatabase.TAG, String.format("View %s has unknown map function: %s", viewName, mapSource));
            return null;
        }
        String reduceSource = (String)viewProps.get("reduce");
        TDViewReduceBlock reduceBlock = null;
        if(reduceSource != null) {
            reduceBlock = TDView.getCompiler().compileReduceFunction(reduceSource, language);
            if(reduceBlock == null) {
                Log.w(TDDatabase.TAG, String.format("View %s has unknown reduce function: %s", viewName, reduceBlock));
                return null;
            }
        }

        TDView view = db.getViewNamed(viewName);
        view.setMapReduceBlocks(mapBlock, reduceBlock, "1");
        String collation = (String)viewProps.get("collation");
        if("raw".equals(collation)) {
            view.setCollation(TDViewCollation.TDViewCollationRaw);
        }
        return view;
    }

    public TDStatus queryDesignDoc(String designDoc, String viewName, List<Object> keys) {
        String tdViewName = String.format("%s/%s", designDoc, viewName);
        TDView view = db.getExistingViewNamed(tdViewName);
        if(view == null || view.getMapBlock() == null) {
            // No TouchDB view is defined, or it hasn't had a map block assigned;
            // see if there's a CouchDB view definition we can compile:
            TDRevision rev = db.getDocumentWithIDAndRev(String.format("_design/%s", designDoc), null, EnumSet.noneOf(TDContentOptions.class));
            if(rev == null) {
                return new TDStatus(TDStatus.NOT_FOUND);
            }
            Map<String,Object> views = (Map<String,Object>)rev.getProperties().get("views");
            Map<String,Object> viewProps = (Map<String,Object>)views.get(viewName);
            if(viewProps == null) {
                return new TDStatus(TDStatus.NOT_FOUND);
            }
            // If there is a CouchDB view, see if it can be compiled from source:
            view = compileView(tdViewName, viewProps);
            if(view == null) {
                return new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);
            }
        }

        TDQueryOptions options = new TDQueryOptions();
        if(!getQueryOptions(options)) {
            return new TDStatus(TDStatus.BAD_REQUEST);
        }
        if(keys != null) {
            options.setKeys(keys);
        }

        TDStatus status = view.updateIndex();
        if(!status.isSuccessful()) {
            return status;
        }

        long lastSequenceIndexed = view.getLastSequenceIndexed();

        // Check for conditional GET and set response Etag header:
        if(keys == null) {
            long eTag = options.isIncludeDocs() ? db.getLastSequence() : lastSequenceIndexed;
            if(cacheWithEtag(String.format("%d", eTag))) {
                return new TDStatus(TDStatus.NOT_MODIFIED);
            }
        }

        List<Map<String,Object>> rows = view.queryWithOptions(options, status);
        if(rows == null) {
            return status;
        }

        Map<String,Object> responseBody = new HashMap<String,Object>();
        responseBody.put("rows", rows);
        responseBody.put("total_rows", rows.size());
        responseBody.put("offset", options.getSkip());
        if(options.isUpdateSeq()) {
            responseBody.put("update_seq", lastSequenceIndexed);
        }
        connection.setResponseBody(new TDBody(responseBody));
        return new TDStatus(TDStatus.OK);
    }

    public TDStatus do_GET_DesignDocument(TDDatabase _db, String designDocID, String viewName) {
        return queryDesignDoc(designDocID, viewName, null);
    }
}

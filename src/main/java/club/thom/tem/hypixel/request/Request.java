package club.thom.tem.hypixel.request;

import club.thom.tem.TEM;
import club.thom.tem.hypixel.Hypixel;
import club.thom.tem.storage.TEMConfig;
import club.thom.tem.util.MessageUtil;
import club.thom.tem.util.PlayerUtil;
import club.thom.tem.util.RequestUtil;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import org.apache.http.client.utils.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/**
 * Base Request class for all requests to inherit from
 */
public abstract class Request {
    private static final Logger logger = LogManager.getLogger(Request.class);
    protected final Hypixel controller;
    TEMConfig config;
    private final TEM tem;
    public boolean priority;
    private CompletableFuture<Boolean> isComplete = new CompletableFuture<>();
    // Hypixel API
    protected static final String apiUrl = "api.hypixel.net";

    protected URIBuilder urlBuilder;

    public Request(TEM tem, String endpoint, boolean runAsap) {
        // To be appended to the apiUrl
        if (endpoint.charAt(0) != '/') {
            endpoint = "/" + endpoint;
        }
        // Run it as soon as we have a "rate-limit spot" available.
        this.priority = runAsap;
        this.tem = tem;
        this.controller = tem.getApi();
        this.config = tem.getConfig();
        this.urlBuilder = new URIBuilder().setScheme("https").setHost(apiUrl).setPath(endpoint);
    }

    public Request(TEM tem, String endpoint) {
        this(tem, endpoint, false);
    }

    // Parameters, eg user to look-up, api key, etc.
    protected abstract HashMap<String, String> generateParameters();

    private static RequestData requestToReturnedData(String urlString, String agent, HashMap<String, String> params) {
        logger.debug("Creating request to url: {}, params: {}", urlString, params);
        URL url = null;
        JsonObject jsonData;
        HttpsURLConnection uc;
        int status = -1;
        try {
            url = new URL(urlString);
            uc = (HttpsURLConnection) url.openConnection();
            uc.setSSLSocketFactory(RequestUtil.getAllowAllFactory());
            uc.setReadTimeout(10000);
            uc.setConnectTimeout(10000);
            logger.debug("Opening connection to url: {}, params: {}", urlString, params);
            uc.addRequestProperty("User-Agent", agent);
            uc.setRequestProperty("Accept-Encoding", "gzip, deflate");
            logger.debug("Added request property for url: {}, params: {}, code: {}", urlString, params, status);
            uc.connect();
            status = uc.getResponseCode();
            logger.debug("Got response code for url: {}, params: {}, code: {}", urlString, params, status);
            InputStream inputStream;
            if (status != 200) {
                inputStream = new GZIPInputStream(uc.getErrorStream());
            } else {
                inputStream = new GZIPInputStream(uc.getInputStream());
            }
            logger.debug("Parsing data from url: {}, params: {}", urlString, params);
            jsonData = new JsonParser().parse(new InputStreamReader(inputStream)).getAsJsonObject();
            RequestData data = new RequestData(status, uc.getHeaderFields(), jsonData);
            logger.debug("Successfully parsed data from url: {}, params: {} -- data: {}", urlString, params, jsonData);
            return data;
        } catch (IOException | JsonSyntaxException | JsonIOException e) {
            logger.error("Exception when fetching data... (uc maybe null)", e);
            logger.error("URL was: {}", url != null ? url.toExternalForm() : "null url");
            JsonObject errorObject = new JsonObject();
            errorObject.addProperty("success", false);
            errorObject.addProperty("status", status);
            return new RequestData(status, new HashMap<>(), errorObject);
        }
    }

    protected RequestData requestToReturnedData() {
        HashMap<String, String> parameters = generateParameters();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            urlBuilder.addParameter(entry.getKey(), entry.getValue());
        }
        String userUuid = tem.getPlayer().getUUID();
        if (userUuid == null) {
            userUuid = "Unknown Player";
        }
        RequestData returnedData = requestToReturnedData(urlBuilder.toString(), userUuid, parameters);
        if (returnedData.getStatus() == 429) {
            int rateLimitResetSeconds = getNextResetSeconds(returnedData.getHeaders());
            controller.setRateLimited(rateLimitResetSeconds);
            logger.debug("REQUEST-> Rate limited, readding to queue.");
            controller.addToQueue(this);
            isComplete.complete(false);
            isComplete = new CompletableFuture<>();
            return null;
        } else if (returnedData.getStatus() == 403 && !(this instanceof KeyLookupRequest)) {
            // User changed their key since request started!
            if (!parameters.get("key").equals(config.getHypixelKey())) {
                logger.info("REQUEST-> Key changed, readding to queue.");
                controller.addToQueue(this);
                isComplete.complete(false);
                isComplete = new CompletableFuture<>();
                return null;
            }
            // Already a thread waiting to/has sent message.
            if (!controller.hasValidApiKey) {
                logger.info("REQUEST-> Invalid key, readding to queue.");
                controller.addToQueue(this);
                isComplete.complete(false);
                isComplete = new CompletableFuture<>();
                return null;
            }
            // API Key is now invalid.
            controller.hasValidApiKey = false;
            logger.warn("REQUEST-> API KEY IS INVALID!");
            PlayerUtil.waitForPlayer();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                logger.error("Interrupted while sleeping to tell player about invalid key", e);
            }
            MessageUtil.sendMessage(new ChatComponentText(EnumChatFormatting.RED + "Your API key is invalid. " +
                    "You are no longer accruing contributions."));
            logger.info("REQUEST-> Told player about invalid key, readding to queue.");
            controller.addToQueue(this);
            isComplete.complete(false);
            isComplete = new CompletableFuture<>();

            new Thread(() -> {
                try {
                    Thread.sleep(120000);
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting to add new KeyLookupRequest", e);
                }
                KeyLookupRequest request = new KeyLookupRequest(getTem(), config.getHypixelKey(), this.controller);
                this.controller.addToQueue(request);
            }, "TEM-request-new-key-check").start();
            return null;
        } else if (returnedData.getStatus() == -1) {
            logger.error("REQUEST-> -1 status, readding request to queue...");
            controller.addToQueue(this);
            isComplete.complete(false);
            isComplete = new CompletableFuture<>();
            return null;
        }
        // TODO: Check for errors, get rate-limit remaining, etc.
        int rateLimitRemaining = getRateLimitRemaining(returnedData.getHeaders());
        int rateLimitResetSeconds = getNextResetSeconds(returnedData.getHeaders());
        if (rateLimitRemaining != -1 && rateLimitResetSeconds != -1) {
            controller.setRateLimitRemaining(rateLimitRemaining, rateLimitResetSeconds);
        }
        isComplete.complete(true);
        return returnedData;
    }

    // Run this::sendRequest
    public abstract void makeRequest();

    private static int getRateLimitRemaining(Map<String, List<String>> headers) {
        return getIntegerHeader(headers, "RateLimit-Remaining");
    }

    private static int getNextResetSeconds(Map<String, List<String>> headers) {
        int resetSeconds = getIntegerHeader(headers, "RateLimit-Reset");
        if (resetSeconds == -1) {
            // cloudflare
            resetSeconds = getIntegerHeader(headers, "Retry-After");
            if (resetSeconds > 55) {
                return 60 - resetSeconds;
            }
        }
        return resetSeconds;
    }

    private static int getIntegerHeader(Map<String, List<String>> headers, String headerName) {
        if (!headers.containsKey(headerName)) {
            logger.debug("Request had no {} header.", headerName);
            logger.debug("Headers: {}", String.join(", ", headers.keySet()));
            return -1;
        }
        List<String> headerData = headers.get(headerName);
        if (headerData == null || headerData.size() == 0) {
            logger.debug("Request's headerData for {} was null or 0: {}", headerName, headerData);
            return -1;
        }
        return Integer.parseInt(headerData.get(0));
    }

    public CompletableFuture<Boolean> getCompletionFuture() {
        return isComplete;
    }

    public abstract CompletableFuture<?> getFuture();

    public TEM getTem() {
        return tem;
    }
}

package org.embulk.input.gcs;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import com.google.common.collect.ImmutableList;
import com.google.common.base.Optional;
import java.security.GeneralSecurityException;

import org.embulk.config.TaskReport;
import org.embulk.config.Config;
import org.embulk.config.ConfigInject;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.config.TaskReport;
import org.embulk.spi.Exec;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.util.InputStreamFileInput;

import org.slf4j.Logger;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.StorageScopes;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.StorageObject;

public class GcsFileInputPlugin
        implements FileInputPlugin
{
    public interface PluginTask
            extends Task
    {
        @Config("bucket")
        String getBucket();

        @Config("path_prefix")
        String getPathPrefix();

        @Config("last_path")
        @ConfigDefault("null")
        Optional<String> getLastPath();

        @Config("service_account_email")
        String getServiceAccountEmail();

        @Config("application_name")
        @ConfigDefault("\"Embulk GCS input plugin\"")
        String getApplicationName();

        @Config("p12_keyfile_fullpath")
        String getP12KeyfileFullpath();

        List<String> getFiles();
        void setFiles(List<String> files);

        @ConfigInject
        BufferAllocator getBufferAllocator();
    }

    private static final Logger log = Exec.getLogger(GcsFileInputPlugin.class);
    private static HttpTransport httpTransport;
    private static JsonFactory jsonFactory;

    @Override
    public ConfigDiff transaction(ConfigSource config,
                                  FileInputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            jsonFactory = new JacksonFactory();
        } catch (Exception e) {
            log.warn("Could not generate http transport");
        }

        // list files recursively
        task.setFiles(listFiles(task));
        // number of processors is same with number of files
        return resume(task.dump(), task.getFiles().size(), control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
                             int taskCount,
                             FileInputPlugin.Control control)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        control.run(taskSource, taskCount);

        ConfigDiff configDiff = Exec.newConfigDiff();

        List<String> files = new ArrayList<String>(task.getFiles());
        if (files.isEmpty()) {
            // keep the last value if any
            if (task.getLastPath().isPresent()) {
                configDiff.set("last_path", task.getLastPath().get());
            }
        } else {
            Collections.sort(files);
            configDiff.set("last_path", files.get(files.size() - 1));
        }

        return configDiff;
    }

    @Override
    public void cleanup(TaskSource taskSource,
                        int taskCount,
                        List<TaskReport> successTaskReports)
    {
    }

    /**
     * @see https://developers.google.com/accounts/docs/OAuth2ServiceAccount#authorizingrequests
     */
    private static GoogleCredential getCredentialProvider (PluginTask task)
    {
        try {
            // @see https://cloud.google.com/compute/docs/api/how-tos/authorization
            // @see https://developers.google.com/resources/api-libraries/documentation/storage/v1/java/latest/com/google/api/services/storage/STORAGE_SCOPE.html
            GoogleCredential cred = new GoogleCredential.Builder().setTransport(httpTransport)
                    .setJsonFactory(jsonFactory)
                    .setServiceAccountId(task.getServiceAccountEmail())
                    .setServiceAccountScopes(
                            ImmutableList.of(
                                    StorageScopes.DEVSTORAGE_READ_ONLY
                            )
                    )
                    .setServiceAccountPrivateKeyFromP12File(new File(task.getP12KeyfileFullpath()))
                    .build();
            return cred;
        } catch (IOException e) {
            log.warn(String.format("Could not load client secrets file %s", task.getP12KeyfileFullpath()));
        } catch (GeneralSecurityException e) {
            log.warn ("Google Authentication was failed");
        }
        return null;
    }

    private static Storage newGcsClient(PluginTask task)
    {
        GoogleCredential credentials = getCredentialProvider(task);
        Storage client = new Storage.Builder(httpTransport, jsonFactory, credentials)
                .setApplicationName(task.getApplicationName())
                .build();

        return client;
    }

    public List<String> listFiles(PluginTask task)
    {
        Storage client = newGcsClient(task);
        String bucket = task.getBucket();

        return listGcsFilesByPrefix(client, bucket, task.getPathPrefix(), task.getLastPath());
    }

    /**
     * Lists GCS filenames filtered by prefix.
     *
     * The resulting list does not include the file that's size == 0.
     */
    public static List<String> listGcsFilesByPrefix(Storage client, String bucket,
                                             String prefix, Optional<String> lastPath)
    {
        ImmutableList.Builder<String> builder = ImmutableList.builder();

        String lastKey = lastPath.orNull();

        // @see https://cloud.google.com/storage/docs/json_api/v1/objects#resource
        try {
            Storage.Buckets.Get getBucket = client.buckets().get(bucket);
            getBucket.setProjection("full");
            Bucket bk = getBucket.execute();
            if (log.isDebugEnabled()) {
                log.debug("bucket name: " + bucket);
                log.debug("bucket location: " + bk.getLocation());
                log.debug("bucket timeCreated: " + bk.getTimeCreated());
                log.debug("bucket owner: " + bk.getOwner());
            }
        } catch (IOException e) {
            log.warn("Could not access to bucket:" + bucket);
            log.warn(e.getMessage());
        }


        try {
            // @see https://cloud.google.com/storage/docs/json_api/v1/objects/list
            Storage.Objects.List listObjects = client.objects().list(bucket);
            listObjects.setPrefix(prefix);
            listObjects.setPageToken(lastKey);
            do {
                Objects objects = listObjects.execute();
                List<StorageObject> items = objects.getItems();
                if (items == null) {
                    log.info(String.format("No file was found in bucket:%s prefix:%s", bucket, prefix));
                    break;
                }
                for (StorageObject o : items) {
                    if (log.isDebugEnabled()) {
                        log.debug("filename: " + o.getName());
                        log.debug("updated: " + o.getUpdated());
                    }
                    if (o.getSize().compareTo(BigInteger.ZERO) > 0) {
                        builder.add(o.getName());
                    }
                }
                lastKey = objects.getNextPageToken();
                listObjects.setPageToken(lastKey);
            } while (lastKey != null);
        } catch (IOException e) {
            log.warn(String.format("Could not get file list from bucket:%s", bucket));
            log.warn(e.getMessage());
        }

        return builder.build();
    }

    @Override
    public TransactionalFileInput open(TaskSource taskSource, int taskIndex)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);
        return new GcsFileInput(task, taskIndex);
    }

    public static class GcsFileInput
            extends InputStreamFileInput
            implements TransactionalFileInput
    {
        private static class SingleFileProvider
                implements InputStreamFileInput.Provider
        {
            private final Storage client;
            private final String bucket;
            private final String key;
            private boolean opened = false;

            public SingleFileProvider(PluginTask task, int taskIndex)
            {
                this.client = newGcsClient(task);
                this.bucket = task.getBucket();
                this.key = task.getFiles().get(taskIndex);
            }

            @Override
            public InputStream openNext() throws IOException
            {
                if (opened) {
                    return null;
                }
                opened = true;
                Storage.Objects.Get getObject = client.objects().get(bucket, key);

                return getObject.executeMediaAsInputStream();
            }

            @Override
            public void close() { }
        }

        public GcsFileInput(PluginTask task, int taskIndex)
        {
            super(task.getBufferAllocator(), new SingleFileProvider(task, taskIndex));
        }

        public void abort() { }

        public TaskReport commit()
        {
            return Exec.newTaskReport();
        }

        @Override
        public void close() { }
    }
}

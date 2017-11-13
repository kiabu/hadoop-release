/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azuredfs.services;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.microsoft.azure.dfs.rest.client.generated.models.ErrorSchemaException;
import com.microsoft.azure.dfs.rest.client.generated.models.GetFilesystemPropertiesHeaders;
import com.microsoft.azure.dfs.rest.client.generated.models.GetPathPropertiesHeaders;
import com.microsoft.azure.dfs.rest.client.generated.models.ListEntrySchema;
import com.microsoft.azure.dfs.rest.client.generated.models.ListSchema;
import com.microsoft.azure.dfs.rest.client.generated.models.ReadPathHeaders;
import com.microsoft.rest.ServiceResponseWithHeaders;
import okhttp3.Headers;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import rx.Observable;
import rx.functions.Func1;

import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.azuredfs.AzureDistributedFileSystem;
import org.apache.hadoop.fs.azuredfs.constants.FileSystemConfigurations;
import org.apache.hadoop.fs.azuredfs.contracts.exceptions.AzureDistributedFileSystemException;
import org.apache.hadoop.fs.azuredfs.contracts.exceptions.AzureServiceErrorResponseException;
import org.apache.hadoop.fs.azuredfs.contracts.exceptions.InvalidAzureServiceErrorResponseException;
import org.apache.hadoop.fs.azuredfs.contracts.exceptions.InvalidFileSystemPropertyException;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsHttpClient;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsHttpClientFactory;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsHttpService;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsStreamFactory;
import org.apache.hadoop.fs.azuredfs.contracts.services.AzureServiceErrorCode;
import org.apache.hadoop.util.StringUtils;

import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static com.google.common.net.HttpHeaders.LAST_MODIFIED;
import static org.apache.hadoop.fs.azuredfs.constants.FileSystemConfigurations.HDI_IS_FOLDER;

@Singleton
@InterfaceAudience.Private
@InterfaceStability.Evolving
final class AdfsHttpServiceImpl implements AdfsHttpService {
  private static final String FILE_SYSTEM = "filesystem";
  private static final String FILE = "file";
  private static final String DIRECTORY = "directory";
  private static final String DATE_TIME_PATTERN = "E, dd MMM yyyy HH:mm:ss 'GMT'";
  private static final String SOURCE_LEASE_ACTION_ACQUIRE = "acquire";
  private static final String COMP_PROPERTIES = "properties";
  private static final int LIST_MAX_RESULTS = 100;
  private static final int MAX_CONCURRENT_THREADS = 20;

  private final AdfsHttpClientFactory adfsHttpClientFactory;
  private final AdfsStreamFactory adfsStreamFactory;
  private final ConcurrentHashMap<AzureDistributedFileSystem, AdfsHttpClient> adfsHttpClientCache;
  private final ThreadPoolExecutor writeExecutorService;
  private final ThreadPoolExecutor readExecutorService;

  @Inject
  AdfsHttpServiceImpl(
      final AdfsHttpClientFactory adfsHttpClientFactory,
      final AdfsStreamFactory adfsStreamFactory) {
    Preconditions.checkNotNull(adfsHttpClientFactory, "adfsHttpClientFactory");
    Preconditions.checkNotNull(adfsStreamFactory, "adfsStreamFactory");

    this.adfsStreamFactory = adfsStreamFactory;
    this.adfsHttpClientCache = new ConcurrentHashMap<>();
    this.adfsHttpClientFactory = adfsHttpClientFactory;
    this.readExecutorService = createThreadPoolExecutor(MAX_CONCURRENT_THREADS);
    this.writeExecutorService = createThreadPoolExecutor(MAX_CONCURRENT_THREADS);
  }

  @Override
  public Hashtable<String, String> getFilesystemProperties(final AzureDistributedFileSystem azureDistributedFileSystem)
      throws AzureDistributedFileSystemException {
    return execute(new Callable<Hashtable<String, String>>() {
      @Override
      public Hashtable<String, String> call() throws Exception {
        return getFilesystemPropertiesAsync(azureDistributedFileSystem).get();
      }
    });
  }

  @Override
  public Future<Hashtable<String, String>> getFilesystemPropertiesAsync(final AzureDistributedFileSystem azureDistributedFileSystem) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Hashtable<String, String> properties = new Hashtable<>();

    final Callable<Hashtable<String, String>> asyncCallable = new Callable<Hashtable<String, String>>() {
      @Override
      public Hashtable<String, String> call() throws Exception {
        Observable<ServiceResponseWithHeaders<Void, GetFilesystemPropertiesHeaders>> response =
            adfsHttpClient.getFilesystemPropertiesWithServiceResponseAsync(
                adfsHttpClient.getSession().getFileSystem(),
                FILE_SYSTEM);

        return response.flatMap(new Func1<ServiceResponseWithHeaders<Void, GetFilesystemPropertiesHeaders>, Observable<Hashtable<String, String>>>() {
          @Override
          public Observable<Hashtable<String, String>> call(ServiceResponseWithHeaders<Void, GetFilesystemPropertiesHeaders>
              voidGetFilesystemPropertiesHeadersServiceResponseWithHeaders) {

            try {
              Headers headers = voidGetFilesystemPropertiesHeadersServiceResponseWithHeaders.response().headers();
              properties.putAll(parseHeaders(headers));
              String xMsProperties = voidGetFilesystemPropertiesHeadersServiceResponseWithHeaders.headers().xMsProperties();
              properties.putAll(parseXMsProperties(xMsProperties));
              return Observable.just(properties);
            }
            catch (Exception ex) {
              return Observable.error(ex);
            }
          }
        }).toBlocking().single();
      }
    };

    return readExecutorService.submit(asyncCallable);
  }

  @Override
  public void setFilesystemProperties(final AzureDistributedFileSystem azureDistributedFileSystem, final Hashtable<String, String> properties) throws
      AzureDistributedFileSystemException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return setFilesystemPropertiesAsync(azureDistributedFileSystem, properties).get();
      }
    });
  }

  @Override
  public Future<Void> setFilesystemPropertiesAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Hashtable<String, String> properties)
      throws
      AzureDistributedFileSystemException {
    if (properties == null || properties.size() == 0) {
      return ConcurrentUtils.constantFuture(null);
    }

    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        String serializedProperties = "";
        Set<String> keys = properties.keySet();
        Iterator<String> itr = keys.iterator();

        while (itr.hasNext()) {
          String key = itr.next();
          serializedProperties += key + "=" + properties.get(key);

          if (itr.hasNext()) {
            serializedProperties += ",";
          }
        }

        return adfsHttpClient.setFilesystemPropertiesAsync(
            COMP_PROPERTIES,
            adfsHttpClient.getSession().getFileSystem(),
            FILE_SYSTEM,
            serializedProperties,
            null,
            0,
            null,
            null).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public Hashtable<String, String> getPathProperties(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    return execute(new Callable<Hashtable<String, String>>() {
      @Override
      public Hashtable<String, String> call() throws Exception {
        return getPathPropertiesAsync(azureDistributedFileSystem, path).get();
      }
    });
  }

  @Override
  public Future<Hashtable<String, String>> getPathPropertiesAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Hashtable<String, String> properties = new Hashtable<>();

    final Callable<Hashtable<String, String>> asyncCallable = new Callable<Hashtable<String, String>>() {
      @Override
      public Hashtable<String, String> call() throws Exception {
        Observable<ServiceResponseWithHeaders<Void, GetPathPropertiesHeaders>> response =
            adfsHttpClient.getPathPropertiesWithServiceResponseAsync(
                adfsHttpClient.getSession().getFileSystem(),
                getRelativePath(path));

        return response.flatMap(new Func1<ServiceResponseWithHeaders<Void, GetPathPropertiesHeaders>, Observable<Hashtable<String, String>>>() {
          @Override
          public Observable<Hashtable<String, String>> call(ServiceResponseWithHeaders<Void, GetPathPropertiesHeaders>
              voidGetPathPropertiesHeadersServiceResponseWithHeaders) {
            try {
              Headers headers = voidGetPathPropertiesHeadersServiceResponseWithHeaders.headResponse().headers();
              properties.putAll(parseHeaders(headers));
              String xMsProperties = voidGetPathPropertiesHeadersServiceResponseWithHeaders.headers().xMsProperties();
              properties.putAll(parseXMsProperties(xMsProperties));
              return Observable.just(properties);
            }
            catch (Exception ex) {
              return Observable.error(ex);
            }
          }
        }).toBlocking().firstOrDefault(new Hashtable<String, String>());
      }
    };

    return readExecutorService.submit(asyncCallable);
  }

  @Override
  public void createFilesystem(final AzureDistributedFileSystem azureDistributedFileSystem) throws AzureDistributedFileSystemException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return createFilesystemAsync(azureDistributedFileSystem).get();
      }
    });
  }

  @Override
  public Future<Void> createFilesystemAsync(final AzureDistributedFileSystem azureDistributedFileSystem) throws AzureDistributedFileSystemException {
    if (!this.adfsHttpClientCache.containsKey(azureDistributedFileSystem)) {
      this.adfsHttpClientCache.put(
          azureDistributedFileSystem,
          this.adfsHttpClientFactory.create(azureDistributedFileSystem));
    }

    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.createFilesystemAsync(
            adfsHttpClient.getSession().getFileSystem(),
            FILE_SYSTEM,
            null,
            null,
            null,
            null,
            null,
            null).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public void deleteFilesystem(final AzureDistributedFileSystem azureDistributedFileSystem) throws AzureDistributedFileSystemException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return deleteFilesystemAsync(azureDistributedFileSystem).get();
      }
    });
  }

  @Override
  public Future<Void> deleteFilesystemAsync(final AzureDistributedFileSystem azureDistributedFileSystem) throws AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.deleteFilesystemAsync(
            adfsHttpClient.getSession().getFileSystem(), FILE_SYSTEM).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public OutputStream createFile(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final boolean overwrite) throws
      AzureDistributedFileSystemException {
    return execute(new Callable<OutputStream>() {
      @Override
      public OutputStream call() throws Exception {
        return createFileAsync(azureDistributedFileSystem, path, overwrite).get();
      }
    });
  }

  @Override
  public Future<OutputStream> createFileAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final boolean overwrite)
      throws AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<OutputStream> asyncCallable = new Callable<OutputStream>() {
      @Override
      public OutputStream call() throws Exception {
        // This needs to be updated with swagger.
        if (!overwrite) {
          try {
            getPathProperties(azureDistributedFileSystem, path);
            // We shouldn't be here
            throw new AzureServiceErrorResponseException(
                AzureServiceErrorCode.PRE_CONDITION_FAILED.getStatusCode(),
                AzureServiceErrorCode.PRE_CONDITION_FAILED.getErrorCode(),
                "Overwrite is false and file exists, createFileAsync should fail.",
                null);
          }
          catch (AzureServiceErrorResponseException ex) {
            if (ex.getStatusCode() != AzureServiceErrorCode.PATH_NOT_FOUND.getStatusCode()) {
              throw ex;
            }
          }
        }

        return adfsHttpClient.createPathAsync(
            getResource(false),
            adfsHttpClient.getSession().getFileSystem(),
            getRelativePath(path)).
            flatMap(new Func1<Void, Observable<OutputStream>>() {
              @Override
              public Observable<OutputStream> call(Void aVoid) {
                try {
                  return Observable.from(openFileForWriteAsync(azureDistributedFileSystem, path, overwrite));
                } catch (AzureDistributedFileSystemException ex) {
                  return Observable.error(ex);
                }
              }
            }).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public Void createDirectory(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws AzureDistributedFileSystemException {
    return execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return createDirectoryAsync(azureDistributedFileSystem, path).get();
      }
    });
  }

  @Override
  public Future<Void> createDirectoryAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.createPathAsync(
            getResource(true),
            adfsHttpClient.getSession().getFileSystem(),
            getRelativePath(path)).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public InputStream openFileForRead(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws AzureDistributedFileSystemException {
    return execute(new Callable<InputStream>() {
      @Override
      public InputStream call() throws Exception {
        return openFileForReadAsync(azureDistributedFileSystem, path).get();
      }
    });
  }

  @Override
  public Future<InputStream> openFileForReadAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    final Callable<InputStream> asyncCallable = new Callable<InputStream>() {
      @Override
      public InputStream call() throws Exception {
        return Observable.from(getFileStatusAsync(azureDistributedFileSystem, path))
            .flatMap(new Func1<FileStatus, Observable<InputStream>>() {
              @Override
              public Observable<InputStream> call(FileStatus fileStatus) {
                if (fileStatus.isDirectory()) {
                  return Observable.error(new AzureServiceErrorResponseException(
                      AzureServiceErrorCode.PRE_CONDITION_FAILED.getStatusCode(),
                      AzureServiceErrorCode.PRE_CONDITION_FAILED.getErrorCode(),
                      "openFileForReadAsync must be used with files and not directories",
                      null));
                }

                return Observable.just(adfsStreamFactory.createReadStream(
                    azureDistributedFileSystem,
                    path,
                    fileStatus.getLen()));
              }
            }).toBlocking().single();
      }
    };

    return readExecutorService.submit(asyncCallable);
  }


  @Override
  public OutputStream openFileForWrite(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final boolean overwrite) throws
      AzureDistributedFileSystemException {
    return execute(new Callable<OutputStream>() {
      @Override
      public OutputStream call() throws Exception {
        return openFileForWriteAsync(azureDistributedFileSystem, path, overwrite).get();
      }
    });
  }

  @Override
  public Future<OutputStream> openFileForWriteAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final boolean overwrite)
      throws AzureDistributedFileSystemException {
    final Callable<OutputStream> asyncCallable = new Callable<OutputStream>() {
      @Override
      public OutputStream call() throws Exception {
        return Observable.from(getFileStatusAsync(azureDistributedFileSystem, path)).flatMap(new Func1<FileStatus, Observable<OutputStream>>() {
          @Override
          public Observable<OutputStream> call(FileStatus fileStatus) {
            if (fileStatus.isDirectory()) {
              return Observable.error(new AzureServiceErrorResponseException(
                  AzureServiceErrorCode.PRE_CONDITION_FAILED.getStatusCode(),
                  AzureServiceErrorCode.PRE_CONDITION_FAILED.getErrorCode(),
                  "openFileForWriteAsync must be used with files and not directories",
                  null));
            }

            long offset = fileStatus.getLen();

            if (overwrite) {
              offset = 0;
            }

            return Observable.just(adfsStreamFactory.createWriteStream(
                azureDistributedFileSystem,
                path,
                offset));
          }
        }).toBlocking().single();
      }
    };

    return readExecutorService.submit(asyncCallable);
  }

  @Override
  public Void readFile(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final long offset, final int length, final byte[]
      readBuffer, final int readBufferOffset) throws
      AzureDistributedFileSystemException {
    return execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return readFileAsync(azureDistributedFileSystem, path, offset, length, readBuffer, readBufferOffset).get();
      }
    });
  }

  @Override
  public Future<Void> readFileAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final long offset, final int length, final
  byte[] targetBuffer, final int targetBufferOffset) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.readPathWithServiceResponseAsync(
            adfsHttpClient.getSession().getFileSystem(),
            getRelativePath(path),
            getRangeHeader(offset, length),
            null,
            FileSystemConfigurations.FS_AZURE_DEFAULT_CONNECTION_READ_TIMEOUT,
            null,
            null).
            flatMap(new Func1<ServiceResponseWithHeaders<InputStream, ReadPathHeaders>, Observable<Void>>() {
              @Override
              public Observable<Void> call(ServiceResponseWithHeaders<InputStream, ReadPathHeaders> inputStreamReadPathHeadersServiceResponseWithHeaders) {
                try {
                  byte[] bytes = inputStreamReadPathHeadersServiceResponseWithHeaders.response().body().bytes();
                  System.arraycopy(bytes, 0, targetBuffer, targetBufferOffset, length);
                  return Observable.just(null);
                } catch (Exception ex) {
                  return Observable.error(ex);
                }
              }
            }).toBlocking().single();
      }
    };

    return readExecutorService.submit(asyncCallable);
  }

  @Override
  public void writeFile(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final byte[] body, final long offset) throws
      AzureDistributedFileSystemException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return writeFileAsync(azureDistributedFileSystem, path, body, offset).get();
      }
    });
  }

  @Override
  public Future<Void> writeFileAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final
  byte[] body, final long offset) throws AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.updatePathAsync(
            getResource(false),
            adfsHttpClient.getSession().getFileSystem(),
            getRelativePath(path),
            "data",
            null,
            Long.valueOf(offset),
            null,
            Integer.toString(body.length),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            body,
            null,
            FileSystemConfigurations.FS_AZURE_DEFAULT_CONNECTION_TIMEOUT,
            null,
            null).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public void flushFile(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final long offset) throws
      AzureDistributedFileSystemException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return flushFileAsync(azureDistributedFileSystem, path, offset).get();
      }
    });
  }

  @Override
  public Future<Void> flushFileAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path, final long offset) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.updatePathAsync(
            getResource(false),
            adfsHttpClient.getSession().getFileSystem(),
            getRelativePath(path),
            "commit",
            null,
            Long.valueOf(offset),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            FileSystemConfigurations.FS_AZURE_DEFAULT_CONNECTION_TIMEOUT,
            null,
            null).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public void renameFile(final AzureDistributedFileSystem azureDistributedFileSystem, final Path source, final Path destination)
      throws
      AzureDistributedFileSystemException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        renameFileAsync(azureDistributedFileSystem, source, destination).get();
        return null;
      }
    });
  }

  @Override
  public Future<Void> renameFileAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path source, final Path destination) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.createPathAsync(
            getResource(false),
            adfsHttpClient.getSession().getFileSystem(),
            getRelativePath(destination),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Path.SEPARATOR + adfsHttpClient.getSession().getFileSystem() + Path.SEPARATOR + getRelativePath(source),
            null,
            null,
            null,
            SOURCE_LEASE_ACTION_ACQUIRE,
            null,
            null,
            null,
            null,
            null,
            FileSystemConfigurations.FS_AZURE_DEFAULT_CONNECTION_TIMEOUT,
            null,
            null).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public void deleteFile(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        deleteFileAsync(azureDistributedFileSystem, path).get();
        return null;
      }
    });
  }

  @Override
  public Future<Void> deleteFileAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<Void> asyncCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return adfsHttpClient.deletePathAsync(getResource(false), adfsHttpClient.getSession().getFileSystem(), getRelativePath(path)).toBlocking().single();
      }
    };

    return writeExecutorService.submit(asyncCallable);
  }

  @Override
  public FileStatus getFileStatus(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws AzureDistributedFileSystemException {
    return execute(new Callable<FileStatus>() {
      @Override
      public FileStatus call() throws Exception {
        return getFileStatusAsync(azureDistributedFileSystem, path).get();
      }
    });
  }

  @Override
  public Future<FileStatus> getFileStatusAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    final Callable<FileStatus> asyncCallable = new Callable<FileStatus>() {
      @Override
      public FileStatus call() throws Exception {
        return Observable.from(getPathPropertiesAsync(azureDistributedFileSystem, path))
            .flatMap(new Func1<Hashtable<String, String>, Observable<FileStatus>>() {
              @Override
              public Observable<FileStatus> call(Hashtable<String, String> properties) {
                return Observable.just(
                    new FileStatus(
                        getContentLength(properties),
                        isDirectory(properties), 0, 0,
                        getLastModifiedTime(properties).getMillis(), path));
              }
            }).toBlocking().single();
      }
    };

    return readExecutorService.submit(asyncCallable);
  }

  @Override
  public FileStatus[] listStatus(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws AzureDistributedFileSystemException {
    return execute(new Callable<FileStatus[]>() {
      @Override
      public FileStatus[] call() throws Exception {
        return listStatusAsync(azureDistributedFileSystem, path).get();
      }
    });
  }

  @Override
  public Future<FileStatus[]> listStatusAsync(final AzureDistributedFileSystem azureDistributedFileSystem, final Path path) throws
      AzureDistributedFileSystemException {
    final AdfsHttpClient adfsHttpClient = this.getFileSystemClient(azureDistributedFileSystem);
    final Callable<FileStatus[]> asyncCallable = new Callable<FileStatus[]>() {
      @Override
      public FileStatus[] call() throws Exception {
        return Observable.from(getFileStatusAsync(azureDistributedFileSystem, path)).flatMap(new Func1<FileStatus, Observable<FileStatus[]>>() {
          @Override
          public Observable<FileStatus[]> call(FileStatus fileStatus) {
            if (!fileStatus.isDirectory()) {
              return Observable.error(new AzureServiceErrorResponseException(
                  AzureServiceErrorCode.PRE_CONDITION_FAILED.getStatusCode(),
                  AzureServiceErrorCode.PRE_CONDITION_FAILED.getErrorCode(),
                  "listStatusAsync must be used with directories and not directories",
                  null));
            }

            String relativePath = getRelativePath(path);
            if (!relativePath.endsWith(File.separator)) {
              relativePath += File.separator;
            }

            return adfsHttpClient.listPathsAsync(
                false,
                adfsHttpClient.getSession().getFileSystem(),
                FILE_SYSTEM,
                relativePath,
                null,
                LIST_MAX_RESULTS,
                null,
                FileSystemConfigurations.FS_AZURE_DEFAULT_CONNECTION_TIMEOUT,
                null,
                null).flatMap(new Func1<ListSchema, Observable<FileStatus[]>>() {
                  @Override
                  public Observable<FileStatus[]> call(ListSchema listSchema) {
                    final List<FileStatus> fileStatus = new LinkedList<>();
                    for (ListEntrySchema entry : listSchema.paths()) {
                      final DateTime dateTime = DateTime.parse(
                          entry.lastModified(),
                          DateTimeFormat.forPattern(DATE_TIME_PATTERN).withZoneUTC());

                      fileStatus.add(
                          new FileStatus(entry.contentLength(),
                              entry.isDirectory() == null ? false : true,
                              0,
                              0,
                              dateTime.getMillis(),
                              azureDistributedFileSystem.makeQualified(new Path(File.separator + entry.name()))));
                    }

                    return Observable.just(fileStatus.toArray(new FileStatus[0]));
                  }
              });
            }
        }).toBlocking().single();
      }
    };

    return readExecutorService.submit(asyncCallable);
  }

  @Override
  public void closeFileSystem(final AzureDistributedFileSystem azureDistributedFileSystem) throws AzureDistributedFileSystemException {
    this.adfsHttpClientCache.remove(azureDistributedFileSystem);
  }

  private String getRelativePath(final Path path) {
    Preconditions.checkNotNull(path, "path");
    final String relativePath = path.toUri().getPath();

    if (relativePath.length() == 0) {
      return relativePath;
    }

    if (relativePath.charAt(0) == Path.SEPARATOR_CHAR) {
      if (relativePath.length() == 1) {
        return "";
      }

      return relativePath.substring(1);
    }

    return relativePath;
  }

  private AdfsHttpClient getFileSystemClient(final AzureDistributedFileSystem azureDistributedFileSystem) {
    Preconditions.checkNotNull(azureDistributedFileSystem, "azureDistributedFileSystem");
    return this.adfsHttpClientCache.get(azureDistributedFileSystem);
  }

  private <T> T execute(final Callable<T> callableRestOperation) throws
      AzureDistributedFileSystemException {

    try {
      return callableRestOperation.call();
    } catch (ErrorSchemaException exception) {
      throw parseErrorSchemaException(exception);
    } catch (ExecutionException exception) {
      Throwable rootCause = ExceptionUtils.getRootCause(exception);
      if (rootCause instanceof ErrorSchemaException) {
        throw parseErrorSchemaException((ErrorSchemaException) rootCause);
      }
      else {
        throw new InvalidAzureServiceErrorResponseException(exception);
      }
    } catch (Exception exception) {
      throw new InvalidAzureServiceErrorResponseException(exception);
    }
  }

  private AzureServiceErrorResponseException parseErrorSchemaException(ErrorSchemaException exception) {
    int statusCode = -1;
    String errorCode = "";
    String errorMessage = "";

    if (exception.response() != null
        && exception.response().raw() != null
        && exception.response().raw().networkResponse() != null) {
      statusCode = exception.response().raw().networkResponse().code();
    }

    if (exception.body() != null && exception.body().error() != null) {
      errorCode = exception.body().error().code();
      errorMessage = exception.body().error().message();
    }

    return new AzureServiceErrorResponseException(statusCode, errorCode, errorMessage, exception);
  }

  private String getResource(final boolean isDirectory) {
    return isDirectory ? DIRECTORY : FILE;
  }

  private long getContentLength(final Hashtable<String, String> properties) {
    if (properties.containsKey(CONTENT_LENGTH)) {
      return Long.parseLong(properties.get(CONTENT_LENGTH));
    }

    return 0;
  }

  private boolean isDirectory(final Hashtable<String, String> properties) {
    return properties.containsKey(HDI_IS_FOLDER) && Boolean.parseBoolean(properties.get(HDI_IS_FOLDER));
  }

  private DateTime getLastModifiedTime(final Hashtable<String, String> properties) {
    return DateTime.parse(
        properties.get(LAST_MODIFIED),
        DateTimeFormat.forPattern(DATE_TIME_PATTERN).withZoneUTC());
  }

  private ThreadPoolExecutor createThreadPoolExecutor(int maxConcurrentThreads) {
    return new ThreadPoolExecutor(
        maxConcurrentThreads,
        maxConcurrentThreads,
        0L,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<Runnable>(),
        new ThreadPoolExecutor.CallerRunsPolicy());
  }

  private Hashtable<String, String> parseHeaders(Headers headers) {
    Map<String, List<String>> headersMap = headers.toMultimap();
    Hashtable<String, String> properties = new Hashtable<>();

    for (Map.Entry<String, List<String>> header : headersMap.entrySet()) {
      String key = WordUtils.capitalizeFully(header.getKey(), "-".toCharArray());
      List<String> values = header.getValue();
      properties.put(key, StringUtils.join(',', values));
    }

    return properties;
  }

  private Hashtable<String, String> parseXMsProperties(String xMsProperties) throws InvalidFileSystemPropertyException {
    Hashtable<String, String> properties = new Hashtable<>();

    if (xMsProperties != null && !xMsProperties.isEmpty()) {
      String[] userProperties = xMsProperties.split(",");

      if (userProperties.length == 0) {
        return properties;
      }

      for (String property : userProperties) {
        if (property.isEmpty()) {
          throw new InvalidFileSystemPropertyException(xMsProperties);
        }

        String[] nameValue = property.split("=");
        if (nameValue.length != 2) {
          throw new InvalidFileSystemPropertyException(xMsProperties);
        }

        properties.put(nameValue[0], nameValue[1]);
      }
    }

    return properties;
  }

  private String getRangeHeader(long offset, long length) {
    return "bytes=" + offset + "-" + (offset + length);
  }
}
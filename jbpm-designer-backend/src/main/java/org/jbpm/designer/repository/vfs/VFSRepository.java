package org.jbpm.designer.repository.vfs;

import org.apache.commons.codec.binary.Base64;
import org.jbpm.designer.repository.*;
import org.jbpm.designer.repository.Repository;
import org.jbpm.designer.repository.impl.AbstractAsset;
import org.jbpm.designer.repository.impl.AssetBuilder;
import org.jbpm.designer.server.service.PathEvent;
import org.jbpm.designer.util.Base64Backport;
import org.kie.commons.io.IOService;
import org.kie.commons.java.nio.IOException;
import org.kie.commons.java.nio.base.options.CommentedOption;
import org.kie.commons.java.nio.file.*;
import org.kie.commons.java.nio.file.DirectoryStream;
import org.kie.commons.java.nio.file.FileSystem;
import org.kie.commons.java.nio.file.Path;
import org.kie.commons.java.nio.file.attribute.BasicFileAttributes;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;

@ApplicationScoped
public class VFSRepository implements Repository {

    private IOService ioService;

    @Inject
    @RequestScoped
    private RepositoryDescriptor descriptor;

    @Inject
    private Event<PathEvent> pathEvent;

    public VFSRepository() {

    }

    @Inject
    public VFSRepository(@Named("ioStrategy") IOService ioService) {
        this.ioService = ioService;

     }

    public RepositoryDescriptor getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(RepositoryDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public String getName() {
        return "vfs";
    }

    public Collection<Directory> listDirectories(String startAt) {
        
        Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + startAt));
        DirectoryStream<Path> directories = ioService.newDirectoryStream(path, new DirectoryStream.Filter<Path>() {

            public boolean accept( final Path entry ) throws IOException {
                if ( Files.isDirectory(entry) ) {
                    return true;
                }
                return false;
            }
        });
        Collection<Directory> foundDirectories = new ArrayList<Directory>();
        Iterator<Path> it = directories.iterator();
        while (it.hasNext()) {
            Path dir = it.next();
            String uniqueId = encodeUniqueId(dir.toUri().toString());
            foundDirectories.add(new Directory(uniqueId, dir.getFileName().toString(), trimLocation(dir)));
        }

        return foundDirectories;
    }

    public Collection<Asset> listAssetsRecursively(String startAt, final Filter filter) {
        final Collection<Asset> foundAssets = new ArrayList<Asset>();
        Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + startAt));

        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

           public FileVisitResult visitFile(Path paths, BasicFileAttributes basicFileAttributes) throws IOException {
               if (filter.accept(paths)) {
                   foundAssets.add(buildAsset(paths, false));
               }
               return FileVisitResult.CONTINUE;
           }

        });

        return foundAssets;
    }

    public Directory createDirectory(String location) {
        Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + location));

        path = ioService.createDirectories(path);
        String uniqueId = encodeUniqueId(path.toUri().toString());
        Directory directory = new Directory(uniqueId, path.getFileName().toString(), trimLocation(path));
        return directory;
    }

    public boolean directoryExists(String directory) {
        Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + directory));

        return ioService.exists(path) && Files.isDirectory(path);
    }

    public boolean deleteDirectory(String directory, boolean failIfNotEmpty) {

        try {
            Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + directory));
            if (!Files.isDirectory(path)) {
                return false;
            }
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path paths, BasicFileAttributes basicFileAttributes) throws IOException {
                    ioService.delete(paths);

                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e == null) {
                        ioService.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        // directory iteration failed
                        throw e;
                    }
                }

            });

            return true;
        } catch (Exception e)  {
            return false;
        }
    }

    public boolean copyDirectory(String sourceDirectory, final String location) {
        if (!directoryExists(sourceDirectory)) {
            throw new IllegalArgumentException("Directory does not exist " + sourceDirectory);
        }
        try {
            final FileSystem fileSystem = descriptor.getFileSystem();
            final Path sourcePath = fileSystem.provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + sourceDirectory));
            if (!Files.isDirectory(sourcePath)) {
                return false;
            }
            final String destinationPathRoot = descriptor.getStringRepositoryRoot() + location + fileSystem.getSeparator() + sourcePath.getFileName().toString();
            Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path destinationPath = fileSystem.provider().getPath(URI.create(destinationPathRoot +
                            fileSystem.getSeparator() + sourcePath.relativize(dir)));
                    fileSystem.provider().createDirectory(destinationPath);

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path currentFile, BasicFileAttributes basicFileAttributes) throws IOException {

                    if (!currentFile.endsWith(".gitignore")) {
                        Path destinationPath = fileSystem.provider().getPath(URI.create(destinationPathRoot +
                                fileSystem.getSeparator() + sourcePath.relativize(currentFile)));
                        createIfNotExists(destinationPath);

                        fileSystem.provider().copy(currentFile, destinationPath, null);
                    }
                    return FileVisitResult.CONTINUE;
                }

            });

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean moveDirectory(String sourceDirectory, final String location, String name) {
        if (!directoryExists(sourceDirectory)) {
            throw new IllegalArgumentException("Directory does not exist " + sourceDirectory);
        }
        try {
            final FileSystem fileSystem = descriptor.getFileSystem();
            final Path sourcePath = fileSystem.provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + sourceDirectory));
            if (!Files.isDirectory(sourcePath)) {
                return false;
            }
            if (name == null) {
                name = sourcePath.getFileName().toString();
            }
            final String destinationPathRoot = descriptor.getStringRepositoryRoot() + location + fileSystem.getSeparator() + name;

            Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path currentFile, BasicFileAttributes basicFileAttributes) throws IOException {
                    Path destinationPath = fileSystem.provider().getPath(URI.create(destinationPathRoot
                            + fileSystem.getSeparator() + sourcePath.relativize(currentFile)));
                    createIfNotExists(destinationPath);
                    fileSystem.provider().move(currentFile, destinationPath, StandardCopyOption.REPLACE_EXISTING);

                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e == null) {
                        try {
                            Path destinationPath = fileSystem.provider().getPath(URI.create(destinationPathRoot
                                    + fileSystem.getSeparator() + sourcePath.relativize(dir)));
                            createIfNotExists(destinationPath);
                            fileSystem.provider().move(dir, destinationPath, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e1) {
                            fileSystem.provider().deleteIfExists(dir);
                        }
                        return FileVisitResult.CONTINUE;
                    } else {
                        // directory iteration failed
                        throw e;
                    }
                }

            });

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Collection<Asset> listAssets(String location) {
        Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + location));
        DirectoryStream<Path> directories = ioService.newDirectoryStream(path, new DirectoryStream.Filter<Path>() {

            public boolean accept( final Path entry ) throws IOException {
                if (!Files.isDirectory(entry)) {
                    return true;
                }
                return false;
            }
        });
        Collection<Asset> foundDirectories = new ArrayList<Asset>();
        Iterator<Path> it = directories.iterator();
        while (it.hasNext()) {
            Asset asset = buildAsset(it.next(), false);
            foundDirectories.add(asset);
        }

        return foundDirectories;
    }

    public Collection<Asset> listAssets(String location, final Filter filter) {
        Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + location));
        DirectoryStream<Path> directories = ioService.newDirectoryStream(path, new DirectoryStream.Filter<Path>() {

            public boolean accept( final Path entry ) throws IOException {

                return filter.accept(entry);
            }
        });
        Collection<Asset> foundDirectories = new ArrayList<Asset>();
        Iterator<Path> it = directories.iterator();
        while (it.hasNext()) {
            Asset asset = buildAsset(it.next(), false);
            foundDirectories.add(asset);
        }

        return foundDirectories;
    }

    public Asset loadAsset(String assetUniqueId) throws AssetNotFoundException {
        String uniqueId = decodeUniqueId(assetUniqueId);
        Path assetPath = getFileSystem(uniqueId).provider().getPath(URI.create(uniqueId));

        Asset asset = buildAsset(assetPath, true);

        return asset;
    }

    public Asset loadAssetFromPath(String location) throws AssetNotFoundException {
        Path path = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + location));

        if (ioService.exists(path)) {
            return loadAsset(path.toUri().toString());
        } else {
            throw new AssetNotFoundException();
        }

    }

    public String createAsset(Asset asset) {
        FileSystem fileSystem = getFileSystem(asset.getUniqueId());
        URI pathURI = null;
        if (asset.getAssetLocation().startsWith(fileSystem.provider().getScheme()) ||
                asset.getAssetLocation().startsWith("default://")) {
            pathURI = URI.create(asset.getAssetLocation()+ "/" +asset.getFullName()) ;
        } else {
            pathURI = URI.create(descriptor.getStringRepositoryRoot() + (asset.getAssetLocation().equals("/")?"":asset.getAssetLocation()) + "/" +asset.getFullName());
        }

        Path filePath = fileSystem.provider().getPath(pathURI);

        if(assetExists(pathURI.toString())) {
            throw new org.kie.commons.java.nio.file.FileAlreadyExistsException( pathURI.toString() );
        }

        createIfNotExists(filePath);
        try {
            CommentedOption commentedOption = new CommentedOption("admin", "Created asset " + asset.getFullName());
            OutputStream outputStream = fileSystem.provider().newOutputStream(filePath, StandardOpenOption.TRUNCATE_EXISTING, commentedOption);
            if(((AbstractAsset)asset).acceptBytes()) {
                outputStream.write(((Asset<byte[]>)asset).getAssetContent());
            } else {
                outputStream.write(asset.getAssetContent().toString().getBytes());
            }
            outputStream.close();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error when creating asset", e);
        }
        return encodeUniqueId(filePath.toUri().toString());
    }

    public String updateAsset(Asset asset) throws AssetNotFoundException {
        String uniqueId = decodeUniqueId(asset.getUniqueId());
        Path filePath = getFileSystem(uniqueId).provider().getPath(URI.create(uniqueId));
        if (!ioService.exists(filePath)) {
            throw new AssetNotFoundException();
        }
        CommentedOption commentedOption = new CommentedOption("admin", "Updated asset " + asset.getFullName());
        if(((AbstractAsset)asset).acceptBytes()) {
            ioService.write(filePath, ((Asset<byte[]>)asset).getAssetContent(), StandardOpenOption.TRUNCATE_EXISTING, commentedOption);
        } else {
            ioService.write(filePath, asset.getAssetContent().toString().getBytes(), StandardOpenOption.TRUNCATE_EXISTING, commentedOption);
        }

        return asset.getUniqueId();
    }

    public boolean deleteAsset(String assetUniqueId) {
        String uniqueId = decodeUniqueId(assetUniqueId);
        try {
            return ioService.deleteIfExists(getFileSystem(uniqueId).provider().getPath(URI.create(uniqueId)));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean deleteAssetFromPath(String path) {
        Path filePath = descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + path));

        return deleteAsset(filePath.toUri().toString());
    }

    public boolean assetExists(String assetUniqueId) {
        String uniqueId = decodeUniqueId(assetUniqueId);
        try {
            return ioService.exists(descriptor.getFileSystem().provider().getPath(URI.create(uniqueId)));
        } catch (Exception e) {
            return ioService.exists(descriptor.getFileSystem().provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + assetUniqueId)));
        }
    }

    public boolean copyAsset(String uniqueId, String location) {
        String decodedUniqueId = decodeUniqueId(uniqueId);
        if (!assetExists(decodedUniqueId)) {
            throw new IllegalArgumentException("Asset does not exist");
        }
        try {
            FileSystem fileSystem = descriptor.getFileSystem();
            Path sourcePath = fileSystem.provider().getPath(URI.create(decodedUniqueId));
            Path destinationPath = fileSystem.provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + location
                    + fileSystem.getSeparator() + sourcePath.getFileName().toString()));
            createIfNotExists(destinationPath);

            CommentedOption commentedOption = new CommentedOption("admin", "Copied asset " + sourcePath.getFileName()
                    + " into " + location);

            fileSystem.provider().copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING,commentedOption);

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean moveAsset(String uniqueId, String location, String name) {
        String decodedUniqueId = decodeUniqueId(uniqueId);
        if (!assetExists(decodedUniqueId)) {
            throw new IllegalArgumentException("Asset does not exist");
        }
        try {
            FileSystem fileSystem = descriptor.getFileSystem();
            Path sourcePath = fileSystem.provider().getPath(URI.create(decodedUniqueId));
            if (name == null) {
                name = sourcePath.getFileName().toString();
            }

            Path destinationPath = fileSystem.provider().getPath(URI.create(descriptor.getStringRepositoryRoot() + location + fileSystem.getSeparator() + name));
            createIfNotExists(destinationPath);
            CommentedOption commentedOption = new CommentedOption("admin", "Moved asset " + sourcePath.getFileName()
                    + " into " + location);
            fileSystem.provider().move(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING, commentedOption);

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    protected Asset buildAsset(Path file, boolean loadContent) {

        String name = file.getFileName().toString();
        String location = trimLocation(file);

        AssetBuilder assetBuilder = AssetBuilderFactory.getAssetBuilder(name);
        BasicFileAttributes attrs = getFileSystem(file.toUri().toString()).provider().readAttributes(file, BasicFileAttributes.class);
        assetBuilder.uniqueId(encodeUniqueId(file.toUri().toString()))
                    .location(location)
                    .creationDate(attrs.creationTime() == null ? "" : new Date(attrs.creationTime().toMillis()).toString())
                    .lastModificationDate(attrs.lastModifiedTime() == null ? "" : new Date(attrs.lastModifiedTime().toMillis()).toString())
                    // TODO some provider specific details
                    .description("")
                    .owner("");

        if (loadContent) {
            if (((AbstractAsset)assetBuilder.getAsset()).acceptBytes()) {
                assetBuilder.content(ioService.readAllBytes(file));
            } else {
                assetBuilder.content(ioService.readAllString(file, Charset.forName("UTF-8")));
            }
        }

        return assetBuilder.decode().getAsset();
    }

    private String decodeUniqueId(String uniqueId) {
        if (Base64Backport.isBase64(uniqueId)) {
            byte[] decoded = Base64.decodeBase64(uniqueId);
            try {
                String uri = new String(decoded, "UTF-8");

                return UriUtils.encode(uri);
            } catch (UnsupportedEncodingException e) {

            }
        }

        return UriUtils.encode(uniqueId);
    }

    private String encodeUniqueId(String uniqueId) {
        try {
            return Base64.encodeBase64URLSafeString(UriUtils.decode(uniqueId).getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }


    private String trimLocation(Path file) {
        String location = "";
        String pathAsString =  file.getParent().toString();
        if(pathAsString.startsWith(descriptor.getRepositoryRoot().getScheme())) {
            location = pathAsString.replaceFirst(descriptor.getStringRepositoryRoot(), "");
        } else {
            location = pathAsString.replaceFirst(descriptor.getRepositoryRootPath().toString(), "");
        }

        if (!location.startsWith(descriptor.getFileSystem().getSeparator())) {
            location = descriptor.getFileSystem().getSeparator() + location;
        }

        return location;
    }

    private void createIfNotExists(Path filePath) {
        if (!ioService.exists(filePath.getParent())) {
            try {
                FileSystem fileSystem = getFileSystem(filePath.toUri().toString());
                fileSystem.provider().createDirectory(filePath.getParent(), null);
            } catch (FileAlreadyExistsException e) {
                // TODO currently git provider does not properly check existence of directories
            }
        }
    }

    private FileSystem getFileSystem(String uri) {
        if (pathEvent != null) {
            pathEvent.fire(new PathEvent(uri));
        }
        return descriptor.getFileSystem();
    }
}

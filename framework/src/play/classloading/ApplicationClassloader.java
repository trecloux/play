package play.classloading;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import play.Logger;
import play.Play;
import play.vfs.VirtualFile;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.UnexpectedException;

/**
 * The application classLoader. Load the classes from
 * the application Java sources files.
 * @author guillaume
 */
public class ApplicationClassloader extends ClassLoader {

    public ApplicationClassloader() {
        super(ApplicationClassloader.class.getClassLoader());
        // Clean the existing classes
        for (ApplicationClass applicationClass : Play.classes.all()) {
            applicationClass.uncompile();
        }
        pathHash = computePathHash();
    }

    @Override
    protected synchronized Class loadClass(String name, boolean resolve) throws ClassNotFoundException {

        // First check if it's an application Class
        Class applicationClass = loadApplicationClass(name);
        if (applicationClass != null) {
            if (resolve) {
                resolveClass(applicationClass);
            }
            return applicationClass;
        }

        // Delegate to the classic classloader
        return super.loadClass(name, resolve);
    }    
    
    // ~~~~~~~~~~~~~~~~~~~~~~~
    public ThreadLocal<List<ApplicationClass>> loadingTracer = new ThreadLocal<List<ApplicationClass>>();

    protected Class loadApplicationClass(String name) {
        ApplicationClass applicationClass = Play.classes.getApplicationClass(name);
        if (applicationClass != null) {
            if (loadingTracer.get() != null) {
                loadingTracer.get().add(applicationClass);
            }
            if (applicationClass.isCompiled()) {
                return applicationClass.javaClass;
            } else {
                if (applicationClass.compile() != null) {
                    applicationClass.enhance();
                    applicationClass.javaClass = defineClass(applicationClass.name, applicationClass.enhancedByteCode, 0, applicationClass.enhancedByteCode.length);
                    resolveClass(applicationClass.javaClass);
                    return applicationClass.javaClass;
                } else {
                    Play.classes.classes.remove(name);
                }
            }
        }
        return null;
    }

    protected byte[] getClassDefinition(String name) {
        name = name.replace(".", "/") + ".class";
        InputStream is = getResourceAsStream(name);
        if (is == null) {
            return null;
        }
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = is.read(buffer, 0, buffer.length)) > 0) {
                os.write(buffer, 0, count);
            }
            return os.toByteArray();
        } catch (Exception e) {
            throw new UnexpectedException(e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                throw new UnexpectedException(e);
            }
        }
    }

    /**
     * Detect Java changes
     */
    public void detectChanges() {
        // Now check for file modification
        List<ApplicationClass> modifieds = new ArrayList<ApplicationClass>();
        for (ApplicationClass applicationClass : Play.classes.all()) {
            if (applicationClass.timestamp < applicationClass.javaFile.lastModified()) {
                applicationClass.refresh();
                modifieds.add(applicationClass);
            }
        }
        List<ClassDefinition> newDefinitions = new ArrayList<ClassDefinition>();
        Map<Class, Integer> annotationsHashes = new HashMap<Class, Integer>();
        for (ApplicationClass applicationClass : modifieds) {
            annotationsHashes.put(applicationClass.javaClass, computeAnnotationsHash(applicationClass.javaClass));
            if(applicationClass.compile() == null) {
                Play.classes.classes.remove(applicationClass.name);
            } else {
                applicationClass.enhance();
                newDefinitions.add(new ClassDefinition(applicationClass.javaClass, applicationClass.enhancedByteCode));
            }
        }
        try {
            HotswapAgent.reload(newDefinitions.toArray(new ClassDefinition[newDefinitions.size()]));
        } catch (ClassNotFoundException e) {
            throw new UnexpectedException(e);
        } catch (UnmodifiableClassException e) {
            throw new UnexpectedException(e);
        }
        // Check new annotations
        for (Class clazz : annotationsHashes.keySet()) {
            if (annotationsHashes.get(clazz) != computeAnnotationsHash(clazz)) {
                throw new RuntimeException("Annotations change !");
            }
        }
        // Now check if there is new classes or removed classes
        int hash = computePathHash();
        if (hash != this.pathHash) {
            // Remove class for deleted files !!
            for(ApplicationClass applicationClass : Play.classes.all()) {
                if(!applicationClass.javaFile.exists()) {
                    Play.classes.classes.remove(applicationClass.name);
                }
            }
            throw new RuntimeException("Path has changed");
        }
    }

    int computeAnnotationsHash(Class clazz) {
        if (clazz == null) {
            return 0;
        }
        StringBuffer buffer = new StringBuffer();
        for (Annotation annotation : clazz.getAnnotations()) {
            buffer.append(annotation.toString());
        }
        for (Field field : clazz.getDeclaredFields()) {
            for (Annotation annotation : field.getAnnotations()) {
                buffer.append(annotation.toString());
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                buffer.append(annotation.toString());
            }
        }
        return buffer.toString().hashCode();
    }
    int pathHash = 0;

    int computePathHash() {
        StringBuffer buf = new StringBuffer();
        for (VirtualFile virtualFile : Play.javaPath) {
            scan(buf, virtualFile);
        }
        return buf.toString().hashCode();
    }

    void scan(StringBuffer buf, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".java")) {
                buf.append(current.getName());
            }
        } else {
            for (VirtualFile virtualFile : current.list()) {
                scan(buf, virtualFile);
            }
        }
    }

    /**
     * Try to load all .java files found.
     * @return The list of well defined Class
     */
    public List<Class> getAllClasses() {
        if (allClasses == null) {
            allClasses = new ArrayList<Class>();
            for (VirtualFile virtualFile : Play.javaPath) {
                getAllClasses(virtualFile);
            }
            for(ApplicationClass applicationClass : Play.classes.all()) {
                allClasses.add(loadApplicationClass(applicationClass.name));
            }
        }
        return allClasses;
    }
    List<Class> allClasses = null;
    
    public List<Class> getAssignableClasses (Class clazz) {
    	List<Class> results = new ArrayList<Class>();
    	for(ApplicationClass c : Play.classes.getAssignableClasses(clazz)) {
            results.add(c.javaClass);
        }
    	return results;
    }
    
    public List<Class> getAnnotatedClasses (Class clazz) {
    	List<Class> results = new ArrayList<Class>();
    	for(ApplicationClass c : Play.classes.getAnnotatedClasses(clazz)) {
            results.add(c.javaClass);
        }
    	return results;
    } 
    
    // ~~~ Intern

    List<Class> getAllClasses(String basePackage) {
        List<Class> res = new ArrayList<Class>();
        for (VirtualFile virtualFile : Play.javaPath) {
            res.addAll(getAllClasses(virtualFile, basePackage));
        }
        return res;
    }

    List<Class> getAllClasses(VirtualFile path) {
        return getAllClasses(path, "");
    }

    List<Class> getAllClasses(VirtualFile path, String basePackage) {
        if (basePackage.length() > 0 && !basePackage.endsWith(".")) {
            basePackage += ".";
        }
        List<Class> res = new ArrayList<Class>();
        for (VirtualFile virtualFile : path.list()) {
            scan(res, basePackage, virtualFile);
        }
        return res;
    }
    
    private void scan(List<Class> classes, String packageName, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".java")) {
                String classname = packageName + current.getName().substring(0, current.getName().length() - 5);
                Class clazz = loadApplicationClass(classname);
                if (clazz == null) {
                    Logger.error("%s was found but class %s cannot be loaded", current, classname);
                } else {
                    classes.add(clazz);
                }
            }
        } else {
            for (VirtualFile virtualFile : current.list()) {
                scan(classes, packageName + current.getName() + ".", virtualFile);
            }
        }
    }
}
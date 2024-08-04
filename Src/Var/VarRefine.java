import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Stream;

public class VarRefine {
    public static void main(String[] args) throws IOException {

        String filePath = "../Datasets/hadoop-trunk/hadoop-mapreduce-project/hadoop-mapreduce-client/hadoop-mapreduce-client-core/src/main/java/org/apache/hadoop/mapred/TextOutputFormat.java";

        String fileContent = new String(Files.readAllBytes(Paths.get(filePath)));

        ASTParser parser = ASTParser.newParser(AST.JLS14);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        parser.setCompilerOptions(JavaCore.getOptions());
        String hadoopRootPath = "../Datasets/hadoop-trunk/";
        String[] srcPaths = {
                hadoopRootPath + "hadoop-common-project/hadoop-common/src/main/java",
                hadoopRootPath + "hadoop-hdfs-project/hadoop-hdfs/src/main/java",
                hadoopRootPath + "hadoop-yarn-project/hadoop-yarn/hadoop-yarn-api/src/main/java",
                hadoopRootPath + "hadoop-mapreduce-project/hadoop-mapreduce-client/hadoop-mapreduce-client-core/src/main/java",
        };
        String[] classpathEntries = {
                "../Datasets/jars/hadoop-annotations-3.3.6.jar",
                "../Datasets/jars/hadoop-auth-3.3.6.jar",
                "../Datasets/jars/hadoop-client-3.3.6.jar",
                "../Datasets/jars/hadoop-common-3.3.6.jar",
                "../Datasets/jars/hadoop-hdfs-client-3.3.6.jar",
                "../Datasets/jars/hadoop-hdfs-3.3.6.jar",
                "../Datasets/jars/hadoop-mapreduce-client-app-3.3.6.jar",
                "../Datasets/jars/hadoop-mapreduce-client-common-3.3.6.jar",
                "../Datasets/jars/hadoop-mapreduce-client-core-3.3.6.jar",
                "../Datasets/jars/hadoop-mapreduce-client-jobclient-3.3.6.jar",
                "../Datasets/jars/hadoop-mapreduce-client-shuffle-3.3.6.jar"
        };
        String[] encodings = new String[classpathEntries.length];
        Arrays.fill(encodings, ""); //
        parser.setEnvironment(srcPaths, classpathEntries, encodings, true);

        parser.setUnitName(getFileNameWithoutExtension(new File(filePath)));

        parser.setSource(fileContent.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding != null && binding.getKind() == IBinding.VARIABLE) {
                    ITypeBinding typeBinding = node.resolveTypeBinding();
                    if (typeBinding != null) {
                        int lineNumber = cu.getLineNumber(node.getStartPosition());
                        System.out.println("Line number: " + lineNumber);
                        System.out.println("Node: " + node.getIdentifier());
                        System.out.println("Definition: " + typeBinding.getQualifiedName() + "." + node.getIdentifier());

                        // Get the declaring class of the variable
                        ITypeBinding declaringClass = getDeclaringClass(typeBinding);
                        if (declaringClass != null) {
                            System.out.println("Declaring class: " + declaringClass.getQualifiedName());

                            // Get the binary name of the class
                            String binaryName = declaringClass.getBinaryName();
                            System.out.println("Binary name: " + binaryName);

                            // Construct the source file path
                            String sourceFilePath = getSourceFilePath(binaryName, srcPaths);
                            if (sourceFilePath != null) {
                                try {
                                    String sourceCode = new String(Files.readAllBytes(Paths.get(sourceFilePath)));
                                    System.out.println("Source code of the declaring class:");
                                    System.out.println(sourceCode);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            } else {
                                System.out.println("Source file not found for: " + binaryName);
                            }
                        } else {
                            System.out.println("Declaring class not found for: " + node.getIdentifier());
                        }
                    }
                }
                return super.visit(node);
            }
        });


    }

    private static String getFileNameWithoutExtension(File file) {
        String fileName = file.getName();
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);
    }

    private static String getSourceFilePath(String binaryName, String[] srcPaths) {
        String relativePath = binaryName.replace('.', File.separatorChar) + ".java";
        for (String srcPath : srcPaths) {
            Path sourceFilePath = Paths.get(srcPath, relativePath);
            if (Files.exists(sourceFilePath)) {
                return sourceFilePath.toString();
            }
        }
        return null;
    }

    private static ITypeBinding getDeclaringClass(ITypeBinding typeBinding) {
        if (typeBinding == null) {
            return null;
        }
        if (typeBinding.getDeclaringClass() != null) {
            return getDeclaringClass(typeBinding.getDeclaringClass());
        } else {
            return typeBinding;
        }
    }

    private static String findSourceFileByClassName(String className, String[] srcPaths) {
        for (String srcPath : srcPaths) {
            try (Stream<Path> paths = Files.walk(Paths.get(srcPath))) {
                Optional<Path> sourceFile = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> containsClass(path, className))
                        .findFirst();

                if (sourceFile.isPresent()) {
                    return sourceFile.get().toString();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private static boolean containsClass(Path javaFilePath, String className) {
        try {
            String fileContent = new String(Files.readAllBytes(javaFilePath));
            CompilationUnit cu = parseJavaFile(fileContent);

            final boolean[] found = {false};
            cu.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    if (node.getName().getIdentifier().equals(className)) {
                        found[0] = true;
                        return false;
                    }
                    return super.visit(node);
                }
            });
            return found[0];
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static CompilationUnit parseJavaFile(String fileContent) {
        ASTParser parser = ASTParser.newParser(AST.JLS14);
        parser.setResolveBindings(false);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(fileContent.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }


}

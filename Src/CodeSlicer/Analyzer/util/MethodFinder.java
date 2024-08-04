package Analyzer.util;

import org.eclipse.jdt.core.dom.*;
import java.io.*;
import java.util.*;


public class MethodFinder {
    private String projectSrcDir;
    private Map<String, List<TypeDeclaration>> packageClassMap = new HashMap<>();

    public MethodFinder(String projectSrcDir) {
        this.projectSrcDir = projectSrcDir;
    }

    public void analyze() {
        File root = new File(projectSrcDir);
        listFiles(root);
    }

    private void listFiles(File root) {
        File[] files = root.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                listFiles(file);
            } else if (file.getName().endsWith(".java")) {
                analyzeJavaFile(file);
            }
        }
    }

    private void analyzeJavaFile(File file) {
        try {
            ASTParser parser = ASTParser.newParser(AST.JLS8);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            String source = readFileToString(file);
            parser.setSource(source.toCharArray());
            CompilationUnit cu = (CompilationUnit) parser.createAST(null);

            cu.accept(new ASTVisitor() {
                public boolean visit(PackageDeclaration node) {
                    String packageName = node.getName().getFullyQualifiedName();
                    cu.types().forEach(t -> {
                        if(t instanceof TypeDeclaration) {
                            packageClassMap.computeIfAbsent(packageName, k -> new ArrayList<>()).add((TypeDeclaration)t);
                        }
                    });
                    return false;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void printMethodSource(String packageName, String className, String methodName) {
        List<TypeDeclaration> classes = packageClassMap.get(packageName);
        if (classes != null) {
            for (TypeDeclaration type : classes) {
                if (type.getName().getIdentifier().equals(className)) {
                    for (MethodDeclaration method : type.getMethods()) {
                        if (method.getName().getIdentifier().equals(methodName)) {
                            System.out.println("Source code for method " + methodName + ": " + method.toString());
                            return;
                        }
                    }
                }
                for (TypeDeclaration innerType : type.getTypes()) {
                    if (innerType.getName().getIdentifier().equals(className)) {
                        for (MethodDeclaration method : innerType.getMethods()) {
                            if (method.getName().getIdentifier().equals(methodName)) {
                                System.out.println("Source code for method " + methodName + ": " + method.toString());
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private String readFileToString(File file) throws IOException {
        StringBuilder fileContents = new StringBuilder((int) file.length());
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileContents.append(line).append("\n");
            }
        }
        return fileContents.toString();
    }

    public static void main(String[] args) {
        String projectSrcDir = args[0];
        String packageName = args[1];
        String className = args[2];
        String methodName = args[3];
        MethodFinder analyzer = new MethodFinder(projectSrcDir);
        analyzer.analyze();
        analyzer.printMethodSource(packageName, className, methodName);
    }
}

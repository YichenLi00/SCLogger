import Analyzer.util.MethodFinder;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import org.apache.commons.cli.*;




class CodeSlicer {
    static class Graph {
        private Map<Method, Set<Method>> adjList;

        Graph() {
            this.adjList = new HashMap<>();
        }


        void addEdge(Method src, Method dest) {
            this.adjList.putIfAbsent(src, new HashSet<>());
            this.adjList.get(src).add(dest);
        }

        Set<Method> getAdjMethods(Method method) {
            return this.adjList.getOrDefault(method, Collections.emptySet());
        }
    }

    static class Method {
        String packageName;
        String className;
        String methodName;

        Method(String packageName, String className, String methodName) {
            this.packageName = packageName;
            this.className = className;
            this.methodName = methodName;
        }
        //get package name
        String getPackageName() {
            return packageName;
        }
        //get class name
        String getClassName() {
            return className;
        }
        //get method name
        String getMethodName() {
            return methodName;
        }

        static Method parseMethod(String methodDesc) {
            int lastDotIndex = methodDesc.lastIndexOf(".");
            int lastColonIndex = methodDesc.lastIndexOf(":");
            String fullClassName = methodDesc.substring(0, lastDotIndex);
            String methodName = methodDesc.substring(lastColonIndex + 1);

            int lastDotIndexInFullClassName = fullClassName.lastIndexOf(".");
            String packageName = fullClassName.substring(0, lastDotIndexInFullClassName);
            String className = fullClassName.substring(lastDotIndexInFullClassName + 1);

            return new Method(packageName, className, methodName);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Method method = (Method) o;
            return packageName.equals(method.packageName) && className.equals(method.className) && methodName.equals(method.methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, className, methodName);
        }

        @Override
        public String toString() {
            return "Method{" +
                    "packageName='" + packageName + '\'' +
                    ", className='" + className + '\'' +
                    ", methodName='" + methodName + '\'' +
                    '}';
        }
    }

    static Set<Method> getTwoHopsMethods(Graph callGraph, Method targetMethod) {
        Set<Method> twoHopsMethods = new HashSet<>();
        Set<Method> oneHopMethods = callGraph.getAdjMethods(targetMethod);

        for (Method oneHopMethod : oneHopMethods) {
            twoHopsMethods.addAll(callGraph.getAdjMethods(oneHopMethod));
        }

        return twoHopsMethods;
    }

    static Graph createGraphFromOutput(String filename) {
        Graph callGraph = new Graph();

        try {
            BufferedReader br = new BufferedReader(new FileReader(filename));
            String line;
            while ((line = br.readLine()) != null) {
                String[] methodDescs = line.split(" ");
                Method srcMethod = Method.parseMethod(methodDescs[0].substring(2));
                Method destMethod = Method.parseMethod(methodDescs[1].substring(3));
                callGraph.addEdge(srcMethod, destMethod);
            }
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return callGraph;
    }
    public static void main(String[] args) throws ParseException {
        org.apache.commons.cli.Options options = new Options();

        Option inputOption = new Option("i", "input", true, "input file path");
        inputOption.setRequired(true);
        options.addOption(inputOption);

        Option methodOption = new Option("m", "method", true, "target method signature");
        methodOption.setRequired(true);
        options.addOption(methodOption);

        Option dirOption = new Option("d", "directory", true, "project source directory");
        dirOption.setRequired(true);
        options.addOption(dirOption);

        BasicParser basicParser = new BasicParser();
        CommandLine commandLine = basicParser.parse(options, args);

        try {
            commandLine = basicParser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            System.exit(1);
            return;
        }

        String filename = commandLine.getOptionValue("input");
        String targetMethodSignature = commandLine.getOptionValue("method");
        String projectSrcDir = commandLine.getOptionValue("directory");

        Graph callGraph = createGraphFromOutput(filename);
        Method targetMethod = Method.parseMethod(targetMethodSignature);
        Set<Method> twoHopsMethods = getTwoHopsMethods(callGraph, targetMethod);

        System.out.println("Two hops methods from " + targetMethod + ": " + twoHopsMethods);

        MethodFinder analyzer = new MethodFinder(projectSrcDir);
        analyzer.analyze();
        for (Method method : twoHopsMethods) {
            String packageName = method.getPackageName();
            String className = method.getClassName();
            String methodName = method.getMethodName();
            analyzer.printMethodSource(packageName, className, methodName);
        }
    }


}

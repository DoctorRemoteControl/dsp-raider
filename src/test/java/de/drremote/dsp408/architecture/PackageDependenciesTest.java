package de.drremote.dsp408.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class PackageDependenciesTest {
    @Test void applicationPackagesHaveNoDependencyCycles() throws Exception {
        Path source=Path.of("src/main/java/de/drremote/dsp408");
        Map<String,Set<String>> graph=new TreeMap<>();
        var imports=Pattern.compile("import (?:static )?de\\.drremote\\.dsp408\\.(\\w+)\\.");
        try (var files=Files.walk(source)) {
            for (Path file:files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String owner=source.relativize(file).getName(0).toString();
                Set<String> deps=graph.computeIfAbsent(owner,k -> new TreeSet<>());
                var matches=imports.matcher(Files.readString(file));
                while(matches.find()) if(!owner.equals(matches.group(1))) deps.add(matches.group(1));
            }
        }
        for(String owner:graph.keySet()) visit(owner,graph,new LinkedHashSet<>(),new HashSet<>());
    }

    private void visit(String node,Map<String,Set<String>> graph,Set<String> path,Set<String> checked) {
        assertFalse(path.contains(node),() -> "Package dependency cycle: "+path+" -> "+node);
        if(!checked.add(node)) return;
        path.add(node);
        for(String dependency:graph.getOrDefault(node,Set.of())) visit(dependency,graph,path,checked);
        path.remove(node);
    }
}

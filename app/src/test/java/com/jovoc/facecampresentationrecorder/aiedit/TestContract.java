package com.jovoc.facecampresentationrecorder.aiedit;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.ContractException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Loads the bundled contract.json for JVM tests (assets are not on the test classpath, so read the file). */
public final class TestContract {

    private TestContract() {}

    public static String json() throws IOException {
        File f = new File("src/main/assets/aiedit/contract.json");
        if (!f.isFile()) f = new File("app/src/main/assets/aiedit/contract.json");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static EngineContract load() throws IOException, ContractException {
        return EngineContract.fromJson(json());
    }
}

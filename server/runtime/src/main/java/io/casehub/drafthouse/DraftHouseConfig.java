package io.casehub.drafthouse;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.nio.file.Path;

@ConfigMapping(prefix = "casehub.drafthouse")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DraftHouseConfig {

    Reviewer reviewer();

    Storage storage();

    interface Reviewer {

        String personality();

        @WithDefault("100000")
        int maxDocChars();
    }

    interface Storage {

        /** Storage root for review session files. Defaults to ~/.drafthouse/reviews. */
        @WithDefault("${user.home}/.drafthouse/reviews")
        Path root();
    }
}

package org.eclipse.che.api.core.model.workspace.compose;

/**
 * Describes how to build image for container creation.
 *
 * @author Alexander Garagatyi
 */
public interface BuildContext {
    /**
     * Build context.
     *
     * <p/> Can be git repository, url to Dockerfile.
     */
    String getContext();

    /**
     * Alternate Dockerfile.
     *
     * <p/> Needed if dockerfile has non-default name or is not placed in the root of build context.
     */
    String getDockerfile();

    // TODO add args field
}

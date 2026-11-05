package dev.oreo.modulith.core;

/**
 * A typed, OCaml-inspired module functor. Applying the parameter yields an
 * annotated Minecraft module; the context allows access to running module APIs.
 *
 * @param <P> parameter contract; a Java record may contain multiple arguments
 * @param <M> type of module produced by the functor
 */
@FunctionalInterface
public interface ModuleFunctor<P, M extends MinecraftModule> {
    M apply(P parameters, ModuleContext context) throws Exception;
}

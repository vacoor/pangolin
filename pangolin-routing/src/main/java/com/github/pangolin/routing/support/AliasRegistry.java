package com.github.pangolin.routing.support;

public interface AliasRegistry {

    void registerAlias(String name, String alias);

    void removeAlias(String alias);

    boolean isAlias(String name);

    String[] getAliases(String name);

    String canonicalName(String name);
}

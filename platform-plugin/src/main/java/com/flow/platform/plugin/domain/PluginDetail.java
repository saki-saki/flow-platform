/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flow.platform.plugin.domain;

import com.flow.platform.plugin.domain.adaptor.InputYmlAdaptor;
import com.flow.platform.plugin.domain.envs.PluginEnvKey;
import com.flow.platform.yml.parser.annotations.YmlSerializer;
import com.google.gson.annotations.Expose;
import java.util.List;

/**
 * @author yh@firim
 */
public class PluginDetail {

    @Expose
    @YmlSerializer
    private String language;

    @Expose
    @YmlSerializer(adaptor = InputYmlAdaptor.class)
    private List<PluginEnvKey> inputs;

    @Expose
    @YmlSerializer
    private List<String> outputs;

    @Expose
    @YmlSerializer
    private String run;

    @Expose
    @YmlSerializer
    private String build;
}
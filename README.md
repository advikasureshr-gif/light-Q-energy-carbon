# Carbon-Aware Energy-Efficient Task Scheduler using CloudSim Plus

## Overview

This repository contains the implementation of a **carbon-aware task scheduling framework** developed using **CloudSim Plus**. The project investigates how cloud task scheduling can be optimized by considering not only traditional performance metrics such as makespan and energy consumption, but also the **carbon intensity of electricity** used by data centers.

The scheduler integrates workload traces with time-varying carbon intensity data and evaluates scheduling decisions under different workload conditions.

> **Note:** This repository contains the custom scheduling algorithms and supporting components developed for this research. It is intended to be used alongside the CloudSim Plus simulation framework.

---

## Features

* Carbon-aware task scheduling
* Energy-aware scheduling strategy
* Q-Learning based adaptive scheduler
* Integration with Google Cluster Trace workloads
* Time-varying carbon intensity profiles
* Support for light, medium, and heavy workloads
* Comparative evaluation against baseline scheduling algorithms

---

## Repository Structure


```text
Carbon-Aware-Scheduler/
│
├── scheduler/
│   ├── EnergyAware.java
│   ├── BaselineQLearning.java
│   ├── CarbonIntensityLoader.java
│   ├── GoogleTraceLoader.java
│   ├── MetricsCollection.java
│   ├── QLearningScheduler.java
│   └── RoundRobin.java
│
├── datasets/
│   ├── Google Cluster Trace (processed)
│   ├── prepared_tasks.xlsx
│   └── Carbon intensity profiles/
│       ├── April9.xlsx
│       ├── January4.xlsx
│       ├── January12.xlsx
│       ├── January15.xlsx
│       └── October24.xlsx
│
├── README.md
└── .gitignore
```

---

## Methodology

The scheduler operates in the following stages:

1. Load workload traces.
2. Load carbon intensity profiles.
3. Estimate task execution characteristics.
4. Evaluate candidate virtual machines.
5. Select the VM based on scheduling policy.
6. Execute the simulation and collect metrics.

The scheduling policies aim to reduce:

* Carbon emissions
* Energy consumption
* Makespan

while maintaining acceptable resource utilization.

---

## Technologies

* Java
* CloudSim Plus
* Apache POI
* Maven
* IntelliJ IDEA

---

## Experimental Evaluation

Experiments are conducted under multiple workload scenarios:

* Light workload
* Medium workload
* Heavy workload

Evaluation metrics include:

* Makespan
* Total Energy Consumption
* Estimated Carbon Emissions
* VM Utilization

---

## Research Context

This project was developed as part of undergraduate research in sustainable cloud computing. It explores the integration of carbon-aware scheduling techniques with reinforcement learning approaches to improve environmental sustainability in cloud data centers.

---

## References

Q-learning baseline adapted from:

Jiaxing Xu, Xiaofei Gao and Ningyuan Gao: Adaptive scheduling strategy for cloud computing resources based on Q-learning algorithm (2026)

CloudSim Plus application:

M. C. Silva Filho, R. L. Oliveira, C. C. Monteiro, P. R. M. Inácio, and M. M. Freire. CloudSim Plus: a Cloud Computing Simulation Framework Pursuing Software Engineering Principles for Improved Modularity, Extensibility and Correctness, in IFIP/IEEE International Symposium on Integrated Network Management, 2017, p. 7.

Datasets used:

1. Google Cluster Trace Dataset (2019): https://www.kaggle.com/datasets/derrickmwiti/google-2019-cluster-sample
2. Great Britain Carbon Intensity (2024-Daily Data): https://www.kaggle.com/datasets/gauravkumar2525/great-britain-carbon-intensity-2024-daily-data

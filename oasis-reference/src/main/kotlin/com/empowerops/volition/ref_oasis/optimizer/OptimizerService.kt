package com.empowerops.volition.ref_oasis.optimizer

class OptimizerService(
        private val startAction: IStartAction,
        private val stopAction: IStopAction,
        private val forceStopAction: IForceStopAction,
        private val pauseAction: IPauseAction,
        private val resumeAction: IResumeAction
) : IStartAction by startAction,
        IStopAction by stopAction,
        IForceStopAction by forceStopAction,
        IPauseAction by pauseAction,
        IResumeAction by resumeAction

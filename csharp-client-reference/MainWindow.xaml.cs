using EmpowerOps.Volition.Dto;
using Google.Protobuf.Collections;
using Grpc.Core;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Reflection;
//ook,
// heres my thinking: create a ".net" windows form app, taqke a look at iots references
// try to make this projects references look like that?
using System.Windows.Forms;
using EmpowerOps.Volition.Api;
using MessageBox = System.Windows.MessageBox;

namespace EmpowerOps.Volition.RefClient
{

    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        private readonly IEvaluator _randomNumberEvaluator = new RandomNumberEvaluator();
        private readonly TaskFactory _uiTaskFactory;
        
        private readonly BindingSource _inputSource = new BindingSource();
        private readonly BindingSource _outputSource = new BindingSource();
        private readonly BindingSource _constraintSource = new BindingSource();

        private readonly List<Guid> _runIDs = new List<Guid>();
        
        private AsyncServerStreamingCall<OptimizerGeneratedQueryDTO> _requests;
     
        private string _name = "";
        private int? _timeoutSecs = null;

        private Guid? _activeRunId = null;

        private const string ServerPrefix = "Server:";
        private const string CommandPrefix = ">";

        private int _addCounter = 1;

        private UnaryOptimizer.UnaryOptimizerClient _client;
        private Channel _channel;
        private ChannelState? _channelState;

        public string Version 
        { 
            get 
            {
                return Assembly.GetEntryAssembly().GetName().Version.ToString();
            } 
        }

        public MainWindow()
        {
            //https://grpc.io/docs/quickstart/csharp.html#update-the-client
            _uiTaskFactory = new TaskFactory(TaskScheduler.FromCurrentSynchronizationContext());
            InitializeComponent();
            Window.Title += Assembly.GetExecutingAssembly().GetName().Version.ToString();
            UpdateConnectionStatusAsync();
            UpdateButton();
            UpdateButton();
            ConfigGrid();
        }

        private void ConfigGrid()
        {
            InputGrid.ItemsSource = _inputSource;
            OutputGrid.ItemsSource = _outputSource;
            ConstraintGrid.ItemsSource = _constraintSource;
        }


        private async void StartOptimization_Click(object sender, RoutedEventArgs e)
        {
            _channel = new Channel("localhost:"+Port.Text, ChannelCredentials.Insecure);
            _client = new UnaryOptimizer.UnaryOptimizerClient(_channel);
            UpdateConnectionStatusAsync();

            await Log($"{CommandPrefix} Start Requested");
            try
            {
                var problemDef = new ProblemDefinitionDTO()
                {
                    Inputs =
                    {
                        _inputSource.Cast<Input>().Select(input => new InputParameterDTO()
                        {
                            Name = input.Name,
                            Continuous = new ContinuousDTO()
                            {
                                LowerBound = input.LowerBound,
                                UpperBound = input.UpperBound
                            }
                        })
                    },
                    Evaluables =
                    {
                        new EvaluableNodeDTO() //only 1 simulation node
                        {
                            Simulation = new SimulationNodeDTO()
                            {
                                Inputs = { _inputSource.Cast<Input>().Select(it => new SimulationInputParameterDTO() { Name = it.Name }).ToList() },
                                Outputs = { _outputSource.Cast<Output>().Select(it => new SimulationOutputParameterDTO { Name = it.Name }).ToList() },
                                Name = RegName.Text,
                                AutoMap = true
                            } 
                        },
                        _constraintSource.Cast<Constraint>().Select(it => new EvaluableNodeDTO()
                        {
                            Constraint = new BabelConstraintNodeDTO()
                            {
                                OutputName = it.Name,
                                BooleanExpression = it.Expression
                            }
                        })
                    }
                };

                var settings = new OptimizationSettingsDTO()
                {
                    ConcurrentRunCount = Convert.ToUInt32(ConcurrentRuns.Text)
                };

                if(_timeoutSecs != null)
                {
                    settings.RunTime = new Google.Protobuf.WellKnownTypes.Duration() { Seconds = _timeoutSecs ?? -1 };
                }

                _requests = _client.StartOptimization(new StartOptimizationCommandDTO
                {
                    ProblemDefinition = problemDef,
                    Settings = settings
                });

                var hasNext = await _requests.ResponseStream.MoveNext(CancellationToken.None);
                if( ! hasNext)
                {
                    await Log("failed to read response from StartOptimization stream");
                    return;
                }
                var next = _requests.ResponseStream.Current;

                if (next.OptimizationNotStartedNotification != null)
                {
                    var issues = String.Join(",", next.OptimizationNotStartedNotification.Issues);
                    await Log($"{ServerPrefix} Failed to start: {issues}");
                }
                else if (next.OptimizationStartedNotification != null)
                {
                    await Log($"{ServerPrefix} Started Optimization");
                }
                else throw new Exception($"bad protocol state, expected started-message or not-started-message, but got {next}");

                _uiTaskFactory.StartNew(async () => await HandlingRequestsAsync(_requests));
            }
            catch (RpcException exception)
            {
                await Log($"{ServerPrefix} Error invoke {nameof(_client.StartOptimization)} Exception: {exception}");
            }
        }

        private async void ApplyTimeout_Click(object sender, RoutedEventArgs e)
        {
            if (int.TryParse(TimeoutTextBox.Text, out int timeout) && timeout > 0)
            {
                await ApplyTimeout(timeout);
            }
            else
            {
                TimeoutTextBox.Text = "0";
                await ApplyTimeout(0);
                MessageBox.Show("Invalid time out, please input a postive integer value");
            }

        }

        private async Task ApplyTimeout(int timeout)
        {
            await Log($"{CommandPrefix} Try to apply timeout {timeout}");
            this._timeoutSecs = timeout;
        }

        private void ClearTimeout_Click(object sender, RoutedEventArgs e)
        {
            TimeoutTextBox.Text = "";
            _timeoutSecs = null;
        }

        private async void StopOptimization_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                await Log($"{CommandPrefix} Request Stop - ID:{_activeRunId}");
                var stopOptimizationResponseDto = await _client.StopOptimizationAsync(new StopOptimizationCommandDTO()
                {
                    Name = _name,
                    RunID = _activeRunId?.toUuidDto()
                });

                await Log($"{ServerPrefix} Stop Request received - Stopping RunID:{stopOptimizationResponseDto.RunID}");
                _activeRunId = null;
            }
            catch (Exception ex)
            {
                await Log($"{ServerPrefix} Error invoke {nameof(_client.StopOptimization)} Exception: {ex}");
            }
        }

        private void RequestResult_Click(object sender, RoutedEventArgs e)
        {
            throw new Exception("blam!");   
            // var resultResponseDto = await RequestRunResult(_latestRunID);
            // Log($"{_serverPrefix} Result:{Environment.NewLine}{String.Join(Environment.NewLine, resultResponseDto.Frontier)}");
        }

        private async Task<OptimizationResultsResponseDTO> RequestRunResult(Guid runId)
        {
            await Log($"{CommandPrefix} Request run result - ID:{runId}");
            return await _client.RequestRunResultAsync(new OptimizationResultsQueryDTO()
            {
                RunID = runId.toUuidDto()
            });
        }

        private Task UpdateConnectionStatusAsync()
        {
            return _uiTaskFactory.StartNew(async () =>
            {
                ChannelState channelState = _channel?.State ?? ChannelState.Idle;
                ConnectionStatus.Text = $"Connection: {channelState}";
                while (_channel != null)
                {
                    await _channel?.WaitForStateChangedAsync(channelState);
                    channelState = _channel.State;
                    ConnectionStatus.Text = $"Connection: {channelState}";
                }                
            });
        }

        private void UpdateButton()
        {
            RegistrationStatus.Text = "asdf";
        }

        private async Task HandlingRequestsAsync(AsyncServerStreamingCall<OptimizerGeneratedQueryDTO> requests)
        {
            //request for inputs, do the simulation, return result, repeat
            var requestsResponseStream = requests.ResponseStream;
            try
            {
                while (await requestsResponseStream.MoveNext(CancellationToken.None))
                {
                    HandleRequestAsync(requestsResponseStream.Current);
                }

                await Log($"{CommandPrefix} Query Closed, plugin has been unregistered by server");
            }
            catch (Exception e)
            {
                await Log($"{CommandPrefix} Error happened when reading from request stream, unregistered\n{e}");
            }
            finally
            {
                _name = "";
                _requests = null;
                UpdateButton();
            }

        }

        private async Task HandleRequestAsync(OptimizerGeneratedQueryDTO request)
        {
            try
            {
                switch (request.PurposeCase)
                {
                    case OptimizerGeneratedQueryDTO.PurposeOneofCase.OptimizationStartedNotification:
                        _activeRunId = request.OptimizationStartedNotification.RunID.toGuid();
                        break;
                    case OptimizerGeneratedQueryDTO.PurposeOneofCase.EvaluationRequest:
                        await Log($"{ServerPrefix} Request Evaluation");
                        await EvaluateAsync(request);
                        break;
                    case OptimizerGeneratedQueryDTO.PurposeOneofCase.CancelRequest:
                        await Log($"{ServerPrefix} Request Cancel");
                        _randomNumberEvaluator.Cancel();
                        break;
                    case OptimizerGeneratedQueryDTO.PurposeOneofCase.None:
                        break;
                    default:
                        await Log($"{ServerPrefix} Run Other - {request}");
                        break;
                }
            }
            catch (Exception e)
            {
                await _client.OfferErrorResultAsync(new SimulationEvaluationErrorResponseDTO()
                {
                    Name = _name,
                    Message = $"Error handling request [{request.PurposeCase}]",
                    Exception = e.ToString()
                });
            }

        }

        private async Task EvaluateAsync(OptimizerGeneratedQueryDTO request)
        {
            MapField<string, double> inputs = request.EvaluationRequest.InputVector;
            await Log($"{CommandPrefix} Requested Input: [{inputs}]");

            foreach (Input input in _inputSource)
            {
                input.EvaluatingValue = inputs[input.Name];
            }
            foreach (Output output in _outputSource)
            {
                output.EvaluatingValue = "Evaluating...";
            }
            UpdateBindingSourceOnUI(_inputSource);
            UpdateBindingSourceOnUI(_outputSource);

            await Log($"{CommandPrefix} Evaluating...");

            var result = await _randomNumberEvaluator.EvaluateAsync(inputs, _outputSource.List);

            foreach (Input input in _inputSource)
            {
                input.CurrentValue = inputs[input.Name];
            }

            foreach (Output output in _outputSource)
            {
                if (result.Output.ContainsKey(output.Name))
                {
                    output.CurrentValue = result.Output[output.Name].ToString();
                    output.EvaluatingValue = result.Output[output.Name].ToString();
                }
                else
                {
                    output.CurrentValue = result.Status.ToString();
                    output.EvaluatingValue = result.Status.ToString();
                }
            }

            UpdateBindingSourceOnUI(_inputSource);
            UpdateBindingSourceOnUI(_outputSource);
            switch (result.Status)
            {
                case EvaluationResult.ResultStatus.Succeed:
                    await Log($"{CommandPrefix} Evaluation Succeed [{ToDebugString(result.Output)}]");
                    SimulationEvaluationCompletedResponseDTO request1 = new SimulationEvaluationCompletedResponseDTO
                    {
                        Name = _name,
                        IterationIndex = request.EvaluationRequest.IterationIndex,
                        OutputVector = { result.Output }
                    };
                    await _client.OfferSimulationResultAsync(request1);
                    break;
                case EvaluationResult.ResultStatus.Failed:
                    await Log($"{CommandPrefix} Evaluation Failed [{ToDebugString(result.Output)}]\nException: {result.Exception}");
                    await _client.OfferErrorResultAsync(new SimulationEvaluationErrorResponseDTO()
                    {
                        Name = _name,
                        IterationIndex = request.EvaluationRequest.IterationIndex,
                        Message = $"{CommandPrefix} Evaluation Failed when evaluating [{inputs}]",
                        Exception = result.Exception.ToString(),
                    });
                    break;
                case EvaluationResult.ResultStatus.Canceled:
                    await Log($"{CommandPrefix} Evaluation Canceled");
                    await _client.OfferSimulationResultAsync(new SimulationEvaluationCompletedResponseDTO
                    {
                        Name = _name,
                        IterationIndex = request.EvaluationRequest.IterationIndex,
                        OutputVector = { result.Output }
                    });
                    break;
                default:
                    throw new ArgumentOutOfRangeException();
            }
        }

        public static String ToDebugString(IDictionary<string, double> dictionary)
        {
            return $"{{{ string.Join(",", dictionary.Select(it => $"\"{it.Key}\": {it.Value}").ToArray())}}}";
        }
        
        private async Task Log(string message)
        {
            LogInfoTextBox.Text = LogInfoTextBox.Text += message + "\n";
            LogInfoTextBox.ScrollToEnd();
            Debug.WriteLine(message);
            
            if (_channel.State == ChannelState.Ready && ForwardMessageCheckBox.IsChecked.GetValueOrDefault(false))
            {
                await _client.OfferEvaluationStatusMessageAsync(new StatusMessageCommandDTO() { Name = _name ?? "no_name", Message = message });
            }

        }

        private void UpdateBindingSourceOnUI(BindingSource bindingSource)
        {
            bindingSource.ResetBindings(false);
        }

        private void AddInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Add(new Input
            {
                Name = "x" + _addCounter,
                LowerBound = 0.0,
                UpperBound = 10.0
            });

            _addCounter++;
        }

        private void AddOutput_Button_Click(object sender, RoutedEventArgs e)
        {
            _outputSource.Add(new Output
            {
                Name = "f" + _addCounter
            });

            _addCounter++;
        }

        private void AddConstraint_Button_Click(object sender, RoutedEventArgs e)
        {
            _constraintSource.Add(new Constraint
            {
                Name = "c" + _addCounter,
                Expression = "x1+x2<5"
            });

            _addCounter++;
        }

        private void RemoveConstraint_Button_Click(object sender, RoutedEventArgs e)
        {
            _constraintSource.Remove(ConstraintGrid.SelectedItem);
        }

        private void RemoveInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Remove(InputGrid.SelectedItem);
        }

        private void RemoveOutput_Button_Click(object sender, RoutedEventArgs e)
        {
            _outputSource.Remove(OutputGrid.SelectedItem);
        }

        private void FailNextRun_Click(object sender, RoutedEventArgs e)
        {
            _randomNumberEvaluator.SetFailNext();
        }
    }

}
